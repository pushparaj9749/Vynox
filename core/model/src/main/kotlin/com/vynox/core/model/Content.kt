package com.vynox.core.model

import com.vynox.core.animation.Animatable
import com.vynox.core.animation.ColorProperty
import com.vynox.core.animation.ScalarProperty
import com.vynox.core.animation.StaticValue
import com.vynox.core.math.Color
import com.vynox.core.math.Vec2

/**
 * Payload of a layer. Adding a new content type means adding a subclass here, a
 * renderer branch and a serializer branch - the editor UI reads these
 * reflectively through [LayerType] so no editor rewrite is needed.
 */
sealed interface LayerContent {

    /** Asset referenced by this content, when the layer is asset backed. */
    val assetId: String?
        get() = null

    data class VideoContent(
        val asset: String,
        val volume: ScalarProperty = StaticValue(1.0),
        val muted: Boolean = false,
        val fadeIn: Double = 0.0,
        val fadeOut: Double = 0.0
    ) : LayerContent {
        override val assetId: String? get() = asset
    }

    data class ImageContent(
        val asset: String,
        val fit: ContentFit = ContentFit.CONTAIN
    ) : LayerContent {
        override val assetId: String? get() = asset
    }

    data class AudioContent(
        val asset: String,
        val volume: ScalarProperty = StaticValue(1.0),
        val muted: Boolean = false,
        val fadeIn: Double = 0.0,
        val fadeOut: Double = 0.0
    ) : LayerContent {
        override val assetId: String? get() = asset
    }

    data class TextContent(
        val text: String = "Vynox",
        val fontFamily: String = "sans-serif",
        val fontSize: Double = 96.0,
        val fontWeight: Int = 700,
        val italic: Boolean = false,
        val alignment: TextAlign = TextAlign.CENTER,
        val verticalAlign: TextVerticalAlign = TextVerticalAlign.CENTER,
        val letterSpacing: Double = 0.0,
        val lineSpacing: Double = 1.2,
        val color: ColorProperty = StaticValue(Color.WHITE),
        val strokeEnabled: Boolean = false,
        val strokeColor: ColorProperty = StaticValue(Color.BLACK),
        val strokeWidth: Double = 4.0,
        val shadowEnabled: Boolean = false,
        val shadowColor: ColorProperty = StaticValue(Color(0.0, 0.0, 0.0, 0.6)),
        val shadowRadius: Double = 12.0,
        val shadowOffset: Vec2 = Vec2(0.0, 6.0),
        val backgroundEnabled: Boolean = false,
        val backgroundColor: ColorProperty = StaticValue(Color(0.0, 0.0, 0.0, 0.45)),
        val backgroundPadding: Double = 16.0,
        val backgroundRadius: Double = 12.0,
        val maxWidth: Double = 0.0
    ) : LayerContent

    data class ShapeContent(
        val shape: ShapeKind = ShapeKind.RECT,
        val size: Vec2 = Vec2(512.0, 288.0),
        val cornerRadius: Double = 24.0,
        val sides: Int = 5,
        val fillEnabled: Boolean = true,
        val fillColor: ColorProperty = StaticValue(Color.fromHex("#6C5CE7")),
        val strokeEnabled: Boolean = false,
        val strokeColor: ColorProperty = StaticValue(Color.WHITE),
        val strokeWidth: Double = 6.0
    ) : LayerContent

    /** Null / group object: renders nothing but drives children transforms. */
    data class GroupContent(
        val passThrough: Boolean = true
    ) : LayerContent
}

enum class ContentFit { CONTAIN, COVER, STRETCH, NONE }

/** Typed accessors so editor code never has to guess a content type. */
fun Layer.textContent(): LayerContent.TextContent? = content as? LayerContent.TextContent
fun Layer.shapeContent(): LayerContent.ShapeContent? = content as? LayerContent.ShapeContent
fun Layer.videoContent(): LayerContent.VideoContent? = content as? LayerContent.VideoContent
fun Layer.audioContent(): LayerContent.AudioContent? = content as? LayerContent.AudioContent
fun Layer.imageContent(): LayerContent.ImageContent? = content as? LayerContent.ImageContent

fun LayerContent.TextContent.colorAt(time: Double): Color = color.valueAt(time)
fun LayerContent.ShapeContent.fillColorAt(time: Double): Color = fillColor.valueAt(time)
fun LayerContent.ShapeContent.strokeColorAt(time: Double): Color = strokeColor.valueAt(time)

/** Volume envelope of an audio capable layer, including fade and mute. */
fun Layer.volumeAt(time: Double): Double {
    val local = (time - startTime).coerceAtLeast(0.0)
    val base = when (val c = content) {
        is LayerContent.AudioContent -> if (c.muted || muted) 0.0 else c.volume.valueAt(local)
        is LayerContent.VideoContent -> if (c.muted || muted) 0.0 else c.volume.valueAt(local)
        else -> 0.0
    }
    val fadeIn = when (val c = content) {
        is LayerContent.AudioContent -> c.fadeIn
        is LayerContent.VideoContent -> c.fadeIn
        else -> 0.0
    }
    val fadeOut = when (val c = content) {
        is LayerContent.AudioContent -> c.fadeOut
        is LayerContent.VideoContent -> c.fadeOut
        else -> 0.0
    }
    var gain = base
    if (fadeIn > 0.0 && local < fadeIn) gain *= (local / fadeIn).coerceIn(0.0, 1.0)
    if (fadeOut > 0.0 && local > duration - fadeOut) gain *= ((duration - local) / fadeOut).coerceIn(0.0, 1.0)
    return gain.coerceIn(0.0, 4.0)
}

/** Source media time (seconds) for a given composition time. */
fun Layer.sourceTimeAt(time: Double): Double = sourceIn + (time - startTime) * speed
