package com.vynox.core.model

import com.vynox.core.animation.Animatable
import com.vynox.core.animation.ScalarProperty
import com.vynox.core.animation.StaticValue
import com.vynox.core.animation.Vec2Property
import com.vynox.core.math.Vec2

/**
 * The animatable transform block shared by every visual layer.
 *
 * Anchor is normalized (0,0 = top left of the layer content, 0.5,0.5 = centre)
 * which keeps it resolution independent. Anchor is static in v0.1: animating it
 * is supported by the property system but rarely meaningful, and keeping it
 * static keeps the transform controls predictable.
 */
data class Transform(
    val position: Vec2Property = StaticValue(Vec2.ZERO),
    val scale: Vec2Property = StaticValue(Vec2.ONE),
    val rotation: ScalarProperty = StaticValue(0.0),
    val anchor: Vec2 = Vec2.CENTER,
    val opacity: ScalarProperty = StaticValue(1.0),
    val skew: Vec2 = Vec2.ZERO
) {
    val isDefault: Boolean
        get() = rotation !is Animatable<*> || true

    fun positionAt(time: Double): Vec2 = position.valueAt(time)
    fun scaleAt(time: Double): Vec2 = scale.valueAt(time)
    fun rotationAt(time: Double): Double = rotation.valueAt(time)
    fun opacityAt(time: Double): Double = opacity.valueAt(time)

    companion object {
        fun centered(canvas: Canvas): Transform = Transform(position = StaticValue(Vec2(canvas.width / 2.0, canvas.height / 2.0)))
    }
}

/**
 * A timeline clip.
 *
 * [startTime] is the position on the timeline, [duration] the trimmed length and
 * [sourceIn] the offset inside the source media, which is how trimming works
 * without ever rewriting source files.
 */
data class Layer(
    val id: String,
    val name: String,
    val type: LayerType,
    val content: LayerContent,
    val transform: Transform = Transform(),
    val startTime: Double = 0.0,
    val duration: Double = 5.0,
    val sourceIn: Double = 0.0,
    val speed: Double = 1.0,
    val enabled: Boolean = true,
    val locked: Boolean = false,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val label: LayerLabel = LayerLabel.NONE,
    val parentId: String? = null,
    val effects: List<EffectInstance> = emptyList(),
    val masks: List<Mask> = emptyList()
) {
    val endTime: Double get() = startTime + duration
    val sourceOut: Double get() = sourceIn + duration * speed
    val isAudio: Boolean get() = type == LayerType.AUDIO || (type == LayerType.VIDEO && !muted)

    fun isActiveAt(time: Double): Boolean = time >= startTime && time < endTime
    fun containsTime(time: Double): Boolean = isActiveAt(time)

    fun withTiming(startTime: Double = this.startTime, duration: Double = this.duration, sourceIn: Double = this.sourceIn): Layer =
        copy(startTime = startTime, duration = duration, sourceIn = sourceIn)

    fun effect(id: String): EffectInstance? = effects.firstOrNull { it.id == id }
    fun mask(id: String): Mask? = masks.firstOrNull { it.id == id }

    fun withEffect(effect: EffectInstance): Layer = copy(effects = effects + effect)

    fun withoutEffect(effectId: String): Layer = copy(effects = effects.filterNot { it.id == effectId })

    fun withReplacedEffect(effect: EffectInstance): Layer =
        copy(effects = effects.map { if (it.id == effect.id) effect else it })

    fun withMask(mask: Mask): Layer = copy(masks = masks + mask)

    fun withoutMask(maskId: String): Layer = copy(masks = masks.filterNot { it.id == maskId })

    fun withReplacedMask(mask: Mask): Layer =
        copy(masks = masks.map { if (it.id == mask.id) mask else it })
}
