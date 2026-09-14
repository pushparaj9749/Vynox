package com.vynox.core.timeline

import com.vynox.core.animation.Animatable
import com.vynox.core.animation.AnimatedValue
import com.vynox.core.animation.ColorProperty
import com.vynox.core.animation.KeyframeTrack
import com.vynox.core.animation.ScalarProperty
import com.vynox.core.animation.Vec2Property
import com.vynox.core.model.EffectInstance
import com.vynox.core.model.Layer
import com.vynox.core.model.LayerContent

/**
 * Keyframe manipulation that works across every property a layer owns.
 *
 * Keyframes are stored in layer local time (0 = layer in point), so moving a
 * clip on the timeline needs no keyframe work at all, while splitting and
 * trimming do - which is exactly what these helpers encapsulate.
 */
object LayerProperties {

    @Suppress("UNCHECKED_CAST")
    private fun shift(property: Animatable<*>?, delta: Double): Animatable<*>? {
        val track = property?.track ?: return property
        val shifted = track.offsetBy(delta)
        return AnimatedValue(property.valueAt(0.0), shifted as KeyframeTrack<Any?>) as Animatable<*>
    }

    @Suppress("UNCHECKED_CAST")
    private fun trim(property: Animatable<*>?, start: Double, end: Double): Animatable<*>? {
        val track = property?.track ?: return property
        val fallback = property.valueAt(0.0)
        @Suppress("UNCHECKED_CAST")
        val typed = track as KeyframeTrack<Any?>
        val trimmed = typed.trimToRange(start, end, fallback)
        return AnimatedValue(fallback, trimmed as KeyframeTrack<Any?>) as Animatable<*>
    }

    /** All keyframe times of a layer, used by the dope sheet and split logic. */
    fun keyframeTimes(layer: Layer): List<Double> {
        val times = LinkedHashSet<Double>()
        times += layer.transform.position.keyframes.map { it.time }
        times += layer.transform.scale.keyframes.map { it.time }
        times += layer.transform.rotation.keyframes.map { it.time }
        times += layer.transform.opacity.keyframes.map { it.time }
        layer.effects.forEach { effect ->
            effect.parameters.values.forEach { prop -> times += prop.keyframes.map { it.time } }
        }
        contentProperties(layer).forEach { times += it.keyframes.map { kf -> kf.time } }
        return times.sorted()
    }

    fun hasKeyframes(layer: Layer): Boolean = keyframeTimes(layer).isNotEmpty()

    private fun contentProperties(layer: Layer): List<Animatable<*>> = when (val content = layer.content) {
        is LayerContent.TextContent -> listOf(content.color, content.strokeColor, content.shadowColor, content.backgroundColor)
        is LayerContent.ShapeContent -> listOf(content.fillColor, content.strokeColor)
        is LayerContent.VideoContent -> listOf(content.volume)
        is LayerContent.AudioContent -> listOf(content.volume)
        else -> emptyList()
    }

    /** Shifts every keyframe by [delta] seconds. */
    fun shiftKeyframes(layer: Layer, delta: Double): Layer {
        if (delta == 0.0) return layer
        val t = layer.transform
        val transform = t.copy(
            position = shift(t.position, delta) as Vec2Property,
            scale = shift(t.scale, delta) as Vec2Property,
            rotation = shift(t.rotation, delta) as ScalarProperty,
            opacity = shift(t.opacity, delta) as ScalarProperty
        )
        return layer.copy(
            transform = transform,
            effects = layer.effects.map { shiftEffect(it, delta) },
            content = shiftContent(layer.content, delta)
        )
    }

    /** Keeps only keyframes inside [start]..[end], adding boundary keyframes. */
    fun trimKeyframes(layer: Layer, start: Double, end: Double): Layer {
        val t = layer.transform
        val transform = t.copy(
            position = trim(t.position, start, end) as Vec2Property,
            scale = trim(t.scale, start, end) as Vec2Property,
            rotation = trim(t.rotation, start, end) as ScalarProperty,
            opacity = trim(t.opacity, start, end) as ScalarProperty
        )
        return layer.copy(
            transform = transform,
            effects = layer.effects.map { trimEffect(it, start, end) },
            content = trimContent(layer.content, start, end)
        )
    }

    private fun shiftEffect(effect: EffectInstance, delta: Double): EffectInstance =
        effect.copy(parameters = effect.parameters.mapValues { (_, prop) -> shift(prop, delta) ?: prop })

    private fun trimEffect(effect: EffectInstance, start: Double, end: Double): EffectInstance =
        effect.copy(parameters = effect.parameters.mapValues { (_, prop) -> trim(prop, start, end) ?: prop })

    private fun shiftContent(content: LayerContent, delta: Double): LayerContent = when (content) {
        is LayerContent.TextContent -> content.copy(
            color = shift(content.color, delta) as ColorProperty,
            strokeColor = shift(content.strokeColor, delta) as ColorProperty,
            shadowColor = shift(content.shadowColor, delta) as ColorProperty,
            backgroundColor = shift(content.backgroundColor, delta) as ColorProperty
        )
        is LayerContent.ShapeContent -> content.copy(
            fillColor = shift(content.fillColor, delta) as ColorProperty,
            strokeColor = shift(content.strokeColor, delta) as ColorProperty
        )
        is LayerContent.VideoContent -> content.copy(volume = shift(content.volume, delta) as ScalarProperty)
        is LayerContent.AudioContent -> content.copy(volume = shift(content.volume, delta) as ScalarProperty)
        else -> content
    }

    private fun trimContent(content: LayerContent, start: Double, end: Double): LayerContent = when (content) {
        is LayerContent.TextContent -> content.copy(
            color = trim(content.color, start, end) as ColorProperty,
            strokeColor = trim(content.strokeColor, start, end) as ColorProperty,
            shadowColor = trim(content.shadowColor, start, end) as ColorProperty,
            backgroundColor = trim(content.backgroundColor, start, end) as ColorProperty
        )
        is LayerContent.ShapeContent -> content.copy(
            fillColor = trim(content.fillColor, start, end) as ColorProperty,
            strokeColor = trim(content.strokeColor, start, end) as ColorProperty
        )
        is LayerContent.VideoContent -> content.copy(volume = trim(content.volume, start, end) as ScalarProperty)
        is LayerContent.AudioContent -> content.copy(volume = trim(content.volume, start, end) as ScalarProperty)
        else -> content
    }
}
