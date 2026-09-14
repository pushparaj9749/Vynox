package com.vynox.core.model

import com.vynox.core.animation.Animatable

/**
 * One applied effect on a layer.
 *
 * Instances only carry the effect [typeId] plus parameter values; the schema of
 * those parameters lives in the effect registry (core:effects). That separation
 * is what lets new effects be added without touching project serialization.
 */
data class EffectInstance(
    val id: String,
    val typeId: String,
    val enabled: Boolean = true,
    val parameters: Map<String, Animatable<*>> = emptyMap()
) {
    fun withParameter(key: String, value: Animatable<*>): EffectInstance =
        copy(parameters = parameters + (key to value))

    fun withoutParameter(key: String): EffectInstance =
        copy(parameters = parameters - key)

    fun parameter(key: String): Animatable<*>? = parameters[key]
}

/**
 * A mask clips (or reveals) the layer it belongs to. Masks are evaluated before
 * effects so effects bleed consistently with the masked silhouette.
 */
data class Mask(
    val id: String,
    val name: String = "Mask",
    val shape: MaskShape = MaskShape.RECT,
    val mode: MaskMode = MaskMode.ADD,
    val position: com.vynox.core.math.Vec2 = com.vynox.core.math.Vec2.ZERO,
    val size: com.vynox.core.math.Vec2 = com.vynox.core.math.Vec2(512.0, 512.0),
    val rotation: Double = 0.0,
    val feather: Double = 0.0,
    val opacity: Double = 1.0,
    val invert: Boolean = false,
    val enabled: Boolean = true,
    /** Vertices in layer space, used when [shape] is [MaskShape.PATH]. */
    val path: List<com.vynox.core.math.Vec2> = emptyList()
) {
    val rect: com.vynox.core.math.Rect
        get() = com.vynox.core.math.Rect.fromCenter(position, com.vynox.core.math.Size(size.x, size.y))
}
