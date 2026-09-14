package com.vynox.core.vnx

import com.vynox.core.animation.Animatable
import com.vynox.core.animation.AnimatedValue
import com.vynox.core.animation.AngleInterpolator
import com.vynox.core.animation.ColorInterpolator
import com.vynox.core.animation.Interpolation
import com.vynox.core.animation.Keyframe
import com.vynox.core.animation.KeyframeTrack
import com.vynox.core.animation.ScalarInterpolator
import com.vynox.core.animation.StaticValue
import com.vynox.core.animation.ValueInterpolator
import com.vynox.core.animation.Vec2Interpolator
import com.vynox.core.animation.Vec3Interpolator
import com.vynox.core.json.JsonValue
import com.vynox.core.json.jsonObject
import com.vynox.core.json.JsonObjectBuilder
import com.vynox.core.math.Color
import com.vynox.core.math.Vec2
import com.vynox.core.math.Vec3

/**
 * Encodes/decodes animatable properties.
 *
 * A property is written as a typed object so a .vnx stays self describing:
 *   { "type": "vec2", "value": [0,0], "keys": [ { "t": 0, "v": [0,0], "i": "ease_in" } ] }
 * Unknown keyframe easing names degrade to linear instead of failing the load.
 */
object ValueCodecs {

    private const val KEY_TYPE = "type"
    private const val KEY_VALUE = "value"
    private const val KEY_KEYS = "keys"
    private const val KEY_TIME = "t"
    private const val KEY_EASING = "i"
    private const val KEY_CURVE = "c"

    fun typeOf(value: Any?): String = when (value) {
        is Vec2 -> "vec2"
        is Vec3 -> "vec3"
        is Color -> "color"
        else -> "scalar"
    }

    fun interpolatorForType(type: String): ValueInterpolator<*> = when (type) {
        "vec2" -> Vec2Interpolator
        "vec3" -> Vec3Interpolator
        "color" -> ColorInterpolator
        "angle" -> AngleInterpolator
        else -> ScalarInterpolator
    }

    fun encodeColor(color: Color): JsonValue = JsonValue.of(color.argb)

    fun decodeColor(json: JsonValue?): Color =
        Color.fromArgb(json?.asInt() ?: 0xFFFFFFFF.toInt())

    fun encodeVec2(vector: Vec2): JsonValue = JsonValue.Arr(listOf(JsonValue.of(vector.x), JsonValue.of(vector.y)))

    fun decodeVec2(json: JsonValue?): Vec2 {
        val items = json?.asArray() ?: return Vec2.ZERO
        return Vec2(items.getOrNull(0)?.asDouble() ?: 0.0, items.getOrNull(1)?.asDouble() ?: 0.0)
    }

    fun encodeVec3(vector: Vec3): JsonValue =
        JsonValue.Arr(listOf(JsonValue.of(vector.x), JsonValue.of(vector.y), JsonValue.of(vector.z)))

    fun decodeVec3(json: JsonValue?): Vec3 {
        val items = json?.asArray() ?: return Vec3.ONE
        return Vec3(
            items.getOrNull(0)?.asDouble() ?: 1.0,
            items.getOrNull(1)?.asDouble() ?: 1.0,
            items.getOrNull(2)?.asDouble() ?: 1.0
        )
    }

    fun encodeScalar(value: Double): JsonValue = JsonValue.of(value)

    private fun encodeTyped(value: Any?): JsonValue = when (value) {
        is Vec2 -> encodeVec2(value)
        is Vec3 -> encodeVec3(value)
        is Color -> encodeColor(value)
        is Number -> JsonValue.of(value.toDouble())
        is Boolean -> JsonValue.of(value)
        else -> JsonValue.Null
    }

    fun encodeProperty(property: Animatable<*>): JsonValue {
        val sample = property.valueAt(0.0)
        val type = typeOf(sample)
        val builder = JsonObjectBuilder()
        builder.put(KEY_TYPE, type)
        builder.put(KEY_VALUE, encodeTyped(sample))
        if (property.keyframes.isNotEmpty()) {
            builder.put(KEY_KEYS, property.keyframes.map { keyframe ->
                val kf = JsonObjectBuilder()
                kf.put(KEY_TIME, keyframe.time)
                kf.put(KEY_VALUE, encodeTyped(keyframe.value))
                val preset = Interpolation.presetName(keyframe.interpolation)
                kf.put(KEY_EASING, preset)
                if (preset == "custom") {
                    kf.put(KEY_CURVE, listOf(
                        JsonValue.of(keyframe.interpolation.handles.cx1),
                        JsonValue.of(keyframe.interpolation.handles.cy1),
                        JsonValue.of(keyframe.interpolation.handles.cx2),
                        JsonValue.of(keyframe.interpolation.handles.cy2)
                    ))
                }
                kf.build()
            })
        }
        return builder.build()
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeProperty(json: JsonValue?): Animatable<*> {
        if (json == null || json.isNull) return StaticValue(0.0)
        if (json !is JsonValue.Obj) {
            // Bare value: treat as a static scalar/vec2 depending on the payload.
            return when {
                json is JsonValue.Arr -> StaticValue(decodeVec2(json))
                else -> StaticValue(json.asDouble() ?: 0.0)
            }
        }
        val type = json.get(KEY_TYPE).asString() ?: "scalar"
        val keys = json.get(KEY_KEYS).asArray()
        if (keys.isEmpty()) {
            return when (type) {
                "vec2" -> StaticValue(decodeVec2(json.get(KEY_VALUE)))
                "vec3" -> StaticValue(decodeVec3(json.get(KEY_VALUE)))
                "color" -> StaticValue(decodeColor(json.get(KEY_VALUE)))
                else -> StaticValue(json.get(KEY_VALUE).asDouble() ?: 0.0)
            }
        }
        val keyframes = keys.mapNotNull { entry ->
            if (entry !is JsonValue.Obj) return@mapNotNull null
            val time = entry.get(KEY_TIME).asDouble() ?: return@mapNotNull null
            val preset = entry.get(KEY_EASING).asString()
            val interpolation = when {
                preset == null -> Interpolation.LINEAR
                preset == "custom" -> {
                    val curve = entry.get(KEY_CURVE).asArray()
                    if (curve.size == 4) Interpolation.bezier(
                        curve[0].asDouble() ?: 0.0,
                        curve[1].asDouble() ?: 0.0,
                        curve[2].asDouble() ?: 1.0,
                        curve[3].asDouble() ?: 1.0
                    ) else Interpolation.LINEAR
                }
                else -> Interpolation.fromPreset(preset)
            }
            when (type) {
                "vec2" -> Keyframe(time, decodeVec2(entry.get(KEY_VALUE)), interpolation)
                "vec3" -> Keyframe(time, decodeVec3(entry.get(KEY_VALUE)), interpolation)
                "color" -> Keyframe(time, decodeColor(entry.get(KEY_VALUE)), interpolation)
                else -> Keyframe(time, entry.get(KEY_VALUE).asDouble() ?: 0.0, interpolation)
            }
        }

        val fallback = when (type) {
            "vec2" -> decodeVec2(json.get(KEY_VALUE))
            "vec3" -> decodeVec3(json.get(KEY_VALUE))
            "color" -> decodeColor(json.get(KEY_VALUE))
            else -> json.get(KEY_VALUE).asDouble() ?: 0.0
        }

        return when (type) {
            "vec2" -> AnimatedValue(fallback as Vec2, KeyframeTrack(Vec2Interpolator, keyframes as List<Keyframe<Vec2>>))
            "vec3" -> AnimatedValue(fallback as Vec3, KeyframeTrack(Vec3Interpolator, keyframes as List<Keyframe<Vec3>>))
            "color" -> AnimatedValue(fallback as Color, KeyframeTrack(ColorInterpolator, keyframes as List<Keyframe<Color>>))
            "angle" -> AnimatedValue(fallback as Double, KeyframeTrack(AngleInterpolator, keyframes as List<Keyframe<Double>>))
            else -> AnimatedValue(fallback as Double, KeyframeTrack(ScalarInterpolator, keyframes as List<Keyframe<Double>>))
        }
    }

    /** Decodes a property and converts it to the expected type when possible. */
    inline fun <reified T> decodeTyped(json: JsonValue?, fallback: T): Animatable<T> {
        val decoded = decodeProperty(json)
        val sample = decoded.valueAt(0.0)
        @Suppress("UNCHECKED_CAST")
        return if (sample is T) decoded as Animatable<T> else StaticValue(fallback)
    }

    fun encodePath(points: List<Vec2>): JsonValue =
        JsonValue.Arr(points.map { encodeVec2(it) })

    fun decodePath(json: JsonValue?): List<Vec2> =
        json?.asArray()?.map { decodeVec2(it) } ?: emptyList()
}
