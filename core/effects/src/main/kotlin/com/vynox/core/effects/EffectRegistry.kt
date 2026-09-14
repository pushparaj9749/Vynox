package com.vynox.core.effects

import com.vynox.core.animation.Animatable
import com.vynox.core.animation.AnimatableOps
import com.vynox.core.animation.AnimatedValue
import com.vynox.core.animation.KeyframeTrack
import com.vynox.core.animation.ScalarProperty
import com.vynox.core.animation.StaticValue
import com.vynox.core.math.Color
import com.vynox.core.math.Vec2
import com.vynox.core.model.EffectInstance
import com.vynox.core.model.Ids

enum class ParamKind { SCALAR, INT, BOOL, COLOR, VEC2, ANGLE, ENUM }

enum class EffectCategory(val label: String) {
    COLOR("Colour"),
    BLUR("Blur & Sharpen"),
    LIGHT("Light"),
    STYLIZE("Stylize"),
    UTILITY("Utility")
}

/**
 * Schema of a single effect parameter. The editor builds its controls from
 * this, serialization writes values using [kind], and the renderer consumes the
 * resolved values - adding an effect never requires editor changes.
 */
data class ParameterDescriptor(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val defaultValue: Any,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 0.01,
    val options: List<String> = emptyList(),
    val unit: String = "",
    val animatable: Boolean = true
) {
    val isColor: Boolean get() = kind == ParamKind.COLOR
    val isNumeric: Boolean get() = kind != ParamKind.COLOR && kind != ParamKind.VEC2
}

data class EffectDefinition(
    val typeId: String,
    val name: String,
    val category: EffectCategory,
    val description: String = "",
    val parameters: List<ParameterDescriptor> = emptyList(),
    /** Identifier of the GPU pass implementing this effect (see gl/Shaders). */
    val shaderId: String = typeId,
    val gpuAccelerated: Boolean = true
) {
    fun descriptor(key: String): ParameterDescriptor? = parameters.firstOrNull { it.key == key }

    /** Creates a new instance populated with defaults. */
    fun instantiate(): EffectInstance = EffectInstance(
        id = Ids.next("fx"),
        typeId = typeId,
        enabled = true,
        parameters = parameters.associate { desc -> desc.key to defaultParameter(desc) }
    )

    companion object {
        fun defaultParameter(desc: ParameterDescriptor): Animatable<*> = when (desc.kind) {
            ParamKind.COLOR -> StaticValue(desc.defaultValue as? Color ?: Color.WHITE)
            ParamKind.VEC2 -> StaticValue(desc.defaultValue as? Vec2 ?: Vec2.ZERO)
            else -> StaticValue((desc.defaultValue as? Number)?.toDouble() ?: 0.0)
        }
    }
}

/** Values of one effect instance at a point in time, ready for the renderer. */
class ResolvedEffect(
    val typeId: String,
    val shaderId: String,
    val enabled: Boolean,
    private val values: Map<String, Any>
) {
    fun double(key: String, fallback: Double = 0.0): Double = values[key] as? Double ?: fallback
    fun int(key: String, fallback: Int = 0): Int = (values[key] as? Double)?.toInt() ?: fallback
    fun bool(key: String, fallback: Boolean = false): Boolean = ((values[key] as? Double) ?: (if (fallback) 1.0 else 0.0)) >= 0.5
    fun color(key: String, fallback: Color = Color.WHITE): Color = values[key] as? Color ?: fallback
    fun vec2(key: String, fallback: Vec2 = Vec2.ZERO): Vec2 = values[key] as? Vec2 ?: fallback
    val keys: Set<String> get() = values.keys

    companion object {
        val EMPTY = ResolvedEffect("", "", false, emptyMap())
    }
}

/**
 * Central catalogue of effects.
 *
 * Registration happens at start-up (see [BuiltInEffects.register]) but the
 * registry stays open: a plugin/module can call [register] to add effects and
 * the editor UI, serialization and renderer all pick them up automatically.
 */
object EffectRegistry {

    private val definitions = LinkedHashMap<String, EffectDefinition>()

    fun register(definition: EffectDefinition) { definitions[definition.typeId] = definition }

    fun registerAll(items: List<EffectDefinition>) = items.forEach { register(it) }

    fun all(): List<EffectDefinition> = definitions.values.toList()

    fun byCategory(): Map<EffectCategory, List<EffectDefinition>> =
        definitions.values.groupBy { it.category }

    fun get(typeId: String): EffectDefinition? = definitions[typeId]

    fun require(typeId: String): EffectDefinition =
        definitions[typeId] ?: error("Unknown effect type '$typeId'")

    fun contains(typeId: String): Boolean = definitions.containsKey(typeId)

    fun create(typeId: String): EffectInstance? = definitions[typeId]?.instantiate()

    /** Instantiates an effect, seeding parameters from [seed] when present. */
    fun createWith(typeId: String, seed: Map<String, Animatable<*>>): EffectInstance? {
        val definition = definitions[typeId] ?: return null
        val params = definition.parameters.associate { desc ->
            desc.key to (seed[desc.key] ?: EffectDefinition.defaultParameter(desc))
        }
        return EffectInstance(id = Ids.next("fx"), typeId = typeId, enabled = true, parameters = params)
    }

    /** Ensures every parameter declared by the effect exists on the instance. */
    fun normalize(instance: EffectInstance): EffectInstance {
        val definition = definitions[instance.typeId] ?: return instance
        val params = LinkedHashMap<String, Animatable<*>>()
        definition.parameters.forEach { desc ->
            params[desc.key] = instance.parameters[desc.key] ?: EffectDefinition.defaultParameter(desc)
        }
        // Keep unknown keys so a project authored with a newer effect set loses nothing.
        instance.parameters.forEach { (key, value) -> if (!params.containsKey(key)) params[key] = value }
        return instance.copy(parameters = params)
    }

    /** Snapshot of an effect's parameters at [time], with keyframes applied. */
    fun resolve(instance: EffectInstance, time: Double): ResolvedEffect {
        val definition = definitions[instance.typeId]
        val values = HashMap<String, Any>()
        instance.parameters.forEach { (key, property) ->
            val raw = property.valueAt(time)
            values[key] = when (raw) {
                is Color -> raw
                is Vec2 -> raw
                is Number -> raw.toDouble()
                is Boolean -> if (raw) 1.0 else 0.0
                else -> raw as Any
            }
        }
        return ResolvedEffect(instance.typeId, definition?.shaderId ?: instance.typeId, instance.enabled, values)
    }

    fun resolveAll(effects: List<EffectInstance>, time: Double): List<ResolvedEffect> =
        effects.filter { it.enabled }.map { resolve(it, time) }

    /** Human readable label for enum/int parameters. */
    fun optionLabel(instance: EffectInstance, key: String, index: Int): String {
        val definition = definitions[instance.typeId] ?: return index.toString()
        val options = definition.descriptor(key)?.options ?: return index.toString()
        return options.getOrElse(index) { index.toString() }
    }

    fun animatedProperty(instance: EffectInstance, key: String): Animatable<*>? = instance.parameters[key]

    fun withAnimatedProperty(instance: EffectInstance, key: String, value: Animatable<*>): EffectInstance =
        instance.copy(parameters = instance.parameters + (key to value))

    /** Promotes a parameter to animated at [time] (used by the stopwatch button). */
    fun promoteParameter(instance: EffectInstance, key: String, time: Double): EffectInstance {
        val descriptor = definitions[instance.typeId]?.descriptor(key) ?: return instance
        val property = instance.parameters[key] ?: return instance
        val promoted: Animatable<*> = when (descriptor.kind) {
            ParamKind.COLOR -> promoteTyped(property as Animatable<Color>, com.vynox.core.animation.ColorInterpolator, time)
            ParamKind.VEC2 -> promoteTyped(property as Animatable<Vec2>, com.vynox.core.animation.Vec2Interpolator, time)
            ParamKind.ANGLE -> promoteTyped(property as Animatable<Double>, com.vynox.core.animation.AngleInterpolator, time)
            else -> promoteTyped(property as Animatable<Double>, com.vynox.core.animation.ScalarInterpolator, time)
        }
        return instance.copy(parameters = instance.parameters + (key to promoted))
    }

    private fun <T> promoteTyped(property: Animatable<T>, interpolator: com.vynox.core.animation.ValueInterpolator<T>, time: Double): Animatable<T> =
        AnimatableOps.promote(property, interpolator, time)

    fun scalarProperty(instance: EffectInstance, key: String): ScalarProperty =
        instance.parameters[key] as? ScalarProperty ?: StaticValue(0.0)
}
