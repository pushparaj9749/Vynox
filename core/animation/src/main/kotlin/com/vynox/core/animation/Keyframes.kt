package com.vynox.core.animation

import com.vynox.core.math.Color
import com.vynox.core.math.EPSILON
import com.vynox.core.math.Vec2
import com.vynox.core.math.Vec3
import com.vynox.core.math.lerp
import kotlin.math.abs

/** Type tag persisted in .vnx files so tracks can be decoded back safely. */
object ValueType {
    const val SCALAR = "scalar"
    const val VEC2 = "vec2"
    const val VEC3 = "vec3"
    const val COLOR = "color"
    const val ANGLE = "angle"
}

/** Pluggable interpolation for any animatable value type. */
interface ValueInterpolator<T> {
    val typeId: String
    fun interpolate(from: T, to: T, t: Double): T
}

object ScalarInterpolator : ValueInterpolator<Double> {
    override val typeId: String = ValueType.SCALAR
    override fun interpolate(from: Double, to: Double, t: Double): Double = lerp(from, to, t)
}

object AngleInterpolator : ValueInterpolator<Double> {
    override val typeId: String = ValueType.ANGLE

    /** Interpolates along the shortest arc so a 350° -> 10° turn goes forward. */
    override fun interpolate(from: Double, to: Double, t: Double): Double {
        var delta = (to - from) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return normalizeDegrees(from + delta * t)
    }

    /** Wraps an angle into [0, 360) so reported rotation values stay canonical. */
    fun normalizeDegrees(degrees: Double): Double {
        if (degrees.isNaN() || degrees.isInfinite()) return degrees
        var value = degrees % 360.0
        if (value < 0.0) value += 360.0
        return if (value >= 360.0) value - 360.0 else value
    }
}

object Vec2Interpolator : ValueInterpolator<Vec2> {
    override val typeId: String = ValueType.VEC2
    override fun interpolate(from: Vec2, to: Vec2, t: Double): Vec2 = from.lerpTo(to, t)
}

object Vec3Interpolator : ValueInterpolator<Vec3> {
    override val typeId: String = ValueType.VEC3
    override fun interpolate(from: Vec3, to: Vec3, t: Double): Vec3 = from.lerpTo(to, t)
}

object ColorInterpolator : ValueInterpolator<Color> {
    override val typeId: String = ValueType.COLOR
    override fun interpolate(from: Color, to: Color, t: Double): Color = from.lerpTo(to, t)
}

/** A single keyframe: a time, a value and the easing used for the *incoming* segment. */
data class Keyframe<T>(
    val time: Double,
    val value: T,
    val interpolation: Interpolation = Interpolation.LINEAR
) : Comparable<Keyframe<T>> {
    override fun compareTo(other: Keyframe<T>): Int = time.compareTo(other.time)
}

/**
 * Immutable, sorted collection of keyframes for one property.
 *
 * Every mutation returns a new track; nothing here touches project state, which
 * is what makes undo/redo a straight forward snapshot operation.
 */
class KeyframeTrack<T>(
    val interpolator: ValueInterpolator<T>,
    val keyframes: List<Keyframe<T>> = emptyList()
) {

    init {
        keyframes.forEachIndexed { index, kf ->
            if (index > 0) require(kf.time >= keyframes[index - 1].time) { "Keyframes must be sorted by time" }
        }
    }

    val isEmpty: Boolean get() = keyframes.isEmpty()
    val size: Int get() = keyframes.size
    val firstTime: Double? get() = keyframes.firstOrNull()?.time
    val lastTime: Double? get() = keyframes.lastOrNull()?.time
    val times: List<Double> get() = keyframes.map { it.time }

    fun keyframeAt(time: Double, tolerance: Double = 1e-4): Keyframe<T>? =
        keyframes.firstOrNull { abs(it.time - time) <= tolerance }

    fun hasKeyframeAt(time: Double, tolerance: Double = 1e-4): Boolean = keyframeAt(time, tolerance) != null

    /** Interpolated value at [time]; [fallback] is used when the track is empty. */
    fun valueAt(time: Double, fallback: T): T {
        if (keyframes.isEmpty()) return fallback
        if (keyframes.size == 1) return keyframes.first().value
        val first = keyframes.first()
        if (time <= first.time) return first.value
        val last = keyframes.last()
        if (time >= last.time) return last.value
        var index = 0
        while (index < keyframes.size - 1 && keyframes[index + 1].time <= time) index++
        val from = keyframes[index]
        val to = keyframes[index + 1]
        val span = to.time - from.time
        val rawProgress = if (span <= EPSILON) 1.0 else (time - from.time) / span
        val eased = to.interpolation.ease(rawProgress)
        return interpolator.interpolate(from.value, to.value, eased)
    }

    fun withKeyframe(time: Double, value: T, interpolation: Interpolation = Interpolation.LINEAR): KeyframeTrack<T> {
        val existing = keyframeAt(time)
        val updated = if (existing != null) {
            keyframes.map { if (it === existing || it.time == existing.time) it.copy(value = value, interpolation = interpolation) else it }
        } else {
            (keyframes + Keyframe(time, value, interpolation)).sortedBy { it.time }
        }
        return KeyframeTrack(interpolator, updated)
    }

    /** Inserts (or replaces) a keyframe keeping the easing of an existing one. */
    fun setKeyframeValue(time: Double, value: T): KeyframeTrack<T> {
        val existing = keyframeAt(time)
        return if (existing != null) withKeyframe(time, value, existing.interpolation)
        else withKeyframe(time, value, Interpolation.LINEAR)
    }

    fun withoutKeyframe(time: Double): KeyframeTrack<T> =
        KeyframeTrack(interpolator, keyframes.filter { abs(it.time - time) > 1e-4 })

    /**
     * Moves a keyframe to [toTime]. Dropping a keyframe onto another replaces it
     * instead of creating two keyframes at the same time, which would leave the
     * track ambiguous for both rendering and the dope sheet.
     */
    fun moveKeyframe(fromTime: Double, toTime: Double): KeyframeTrack<T> {
        val kf = keyframeAt(fromTime) ?: return this
        val others = keyframes.filter { abs(it.time - fromTime) > 1e-4 && abs(it.time - toTime) > 1e-4 }
        val merged = (others + kf.copy(time = toTime)).sortedBy { it.time }
        return KeyframeTrack(interpolator, merged)
    }

    fun withInterpolation(time: Double, interpolation: Interpolation): KeyframeTrack<T> {
        val kf = keyframeAt(time) ?: return this
        return KeyframeTrack(interpolator, keyframes.map { if (it.time == kf.time) it.copy(interpolation = interpolation) else it })
    }

    fun keyframesBetween(fromTime: Double, toTime: Double): List<Keyframe<T>> =
        keyframes.filter { it.time >= fromTime - EPSILON && it.time <= toTime + EPSILON }

    /** Pastes keyframes (relative to [pasteAt]) replacing anything already there. */
    fun pasteKeyframes(items: List<Keyframe<T>>, pasteAt: Double, baseTime: Double): KeyframeTrack<T> {
        var result = this
        items.forEach { kf ->
            val target = pasteAt + (kf.time - baseTime)
            result = result.withKeyframe(target, kf.value, kf.interpolation)
        }
        return result
    }

    fun offsetBy(delta: Double): KeyframeTrack<T> =
        KeyframeTrack(interpolator, keyframes.map { it.copy(time = (it.time + delta)) }.sortedBy { it.time })

    fun trimToRange(start: Double, end: Double, fallback: T): KeyframeTrack<T> {
        if (keyframes.isEmpty()) return this
        val clipped = keyframes.filter { it.time >= start - EPSILON && it.time <= end + EPSILON }
        val boundaryStart = valueAt(start, fallback)
        val boundaryEnd = valueAt(end, fallback)
        val result = clipped.toMutableList()
        if (result.none { abs(it.time - start) < 1e-4 }) result.add(Keyframe(start, boundaryStart, Interpolation.LINEAR))
        if (result.none { abs(it.time - end) < 1e-4 }) result.add(Keyframe(end, boundaryEnd, Interpolation.LINEAR))
        return KeyframeTrack(interpolator, result.sortedBy { it.time })
    }

    companion object {
        fun <T> empty(interpolator: ValueInterpolator<T>) = KeyframeTrack(interpolator)
    }
}

/**
 * A property is either a constant or an animated track. Editors show a
 * "stopwatch" to promote a constant into an animated property; that promotion
 * keeps the current constant as the value of the first keyframe.
 */
sealed interface Animatable<T> {
    val isAnimated: Boolean
    val keyframes: List<Keyframe<T>>
    val track: KeyframeTrack<T>?
    fun valueAt(time: Double): T
}

data class StaticValue<T>(val value: T) : Animatable<T> {
    override val isAnimated: Boolean = false
    override val keyframes: List<Keyframe<T>> = emptyList()
    override val track: KeyframeTrack<T>? = null
    override fun valueAt(time: Double): T = value
}

data class AnimatedValue<T>(
    val fallback: T,
    val valueTrack: KeyframeTrack<T>
) : Animatable<T> {
    override val isAnimated: Boolean = valueTrack.keyframes.isNotEmpty()
    override val keyframes: List<Keyframe<T>> get() = valueTrack.keyframes
    override val track: KeyframeTrack<T> get() = valueTrack
    override fun valueAt(time: Double): T = valueTrack.valueAt(time, fallback)
}

object AnimatableOps {

    fun <T> of(value: T): Animatable<T> = StaticValue(value)

    fun <T> animated(fallback: T, track: KeyframeTrack<T>): Animatable<T> = AnimatedValue(fallback, track)

    /** Promotes a property to animated, seeding a keyframe with the current value. */
    fun <T> promote(property: Animatable<T>, interpolator: ValueInterpolator<T>, time: Double): Animatable<T> {
        val current = property.valueAt(time)
        val base = property.track ?: KeyframeTrack(interpolator)
        return AnimatedValue(property.valueAt(0.0), base.withKeyframe(time, current))
    }

    fun <T> setKeyframe(property: Animatable<T>, interpolator: ValueInterpolator<T>, time: Double, value: T): Animatable<T> {
        val track = (property.track ?: KeyframeTrack(interpolator)).withKeyframe(time, value, currentInterpolation(property, time))
        return AnimatedValue(property.valueAt(0.0), track)
    }

    /** Inserts (or replaces) a keyframe with an explicit easing curve. */
    fun <T> setKeyframe(
        property: Animatable<T>,
        interpolator: ValueInterpolator<T>,
        time: Double,
        value: T,
        interpolation: Interpolation
    ): Animatable<T> {
        val track = (property.track ?: KeyframeTrack(interpolator)).withKeyframe(time, value, interpolation)
        return AnimatedValue(property.valueAt(0.0), track)
    }

    fun <T> removeKeyframe(property: Animatable<T>, time: Double): Animatable<T> {
        val track = property.track ?: return property
        return AnimatedValue(property.valueAt(0.0), track.withoutKeyframe(time))
    }

    fun <T> moveKeyframe(property: Animatable<T>, fromTime: Double, toTime: Double): Animatable<T> {
        val track = property.track ?: return property
        return AnimatedValue(property.valueAt(0.0), track.moveKeyframe(fromTime, toTime))
    }

    fun <T> setInterpolation(property: Animatable<T>, time: Double, interpolation: Interpolation): Animatable<T> {
        val track = property.track ?: return property
        return AnimatedValue(property.valueAt(0.0), track.withInterpolation(time, interpolation))
    }

    fun <T> currentInterpolation(property: Animatable<T>, time: Double): Interpolation {
        val track = property.track ?: return Interpolation.LINEAR
        return track.keyframeAt(time)?.interpolation ?: Interpolation.LINEAR
    }

    fun <T> collapse(property: Animatable<T>, time: Double): Animatable<T> =
        StaticValue(property.valueAt(time))

    /** Snapshot of every keyframe time in a property, used by the dope sheet. */
    fun <T> times(property: Animatable<T>): List<Double> = property.keyframes.map { it.time }
}

/** Convenience aliases used throughout the layer model. */
typealias ScalarProperty = Animatable<Double>
typealias Vec2Property = Animatable<Vec2>
typealias ColorProperty = Animatable<Color>

fun staticDouble(value: Double): ScalarProperty = StaticValue(value)
fun staticVec2(x: Double, y: Double): Vec2Property = StaticValue(Vec2(x, y))
fun staticColor(argb: Int): ColorProperty = StaticValue(Color.fromArgb(argb))
