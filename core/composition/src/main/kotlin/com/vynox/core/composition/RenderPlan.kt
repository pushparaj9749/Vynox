package com.vynox.core.composition

import com.vynox.core.effects.ResolvedEffect
import com.vynox.core.math.Color
import com.vynox.core.math.Matrix3
import com.vynox.core.math.Rect
import com.vynox.core.math.Size
import com.vynox.core.math.Vec2
import com.vynox.core.model.BlendMode
import com.vynox.core.model.ContentFit
import com.vynox.core.model.LayerType
import com.vynox.core.model.MaskMode
import com.vynox.core.model.MaskShape
import com.vynox.core.model.ShapeKind
import com.vynox.core.model.TextAlign
import com.vynox.core.model.TextVerticalAlign

/** Fully resolved content of a layer at one point in time. */
sealed interface RenderContent {

    data class Video(
        val assetId: String,
        val sourceTime: Double,
        val volume: Double,
        val muted: Boolean
    ) : RenderContent

    data class Image(
        val assetId: String,
        val fit: ContentFit
    ) : RenderContent

    data class Text(
        val text: String,
        val fontFamily: String,
        val fontSize: Double,
        val fontWeight: Int,
        val italic: Boolean,
        val alignment: TextAlign,
        val verticalAlign: TextVerticalAlign,
        val letterSpacing: Double,
        val lineSpacing: Double,
        val color: Color,
        val strokeEnabled: Boolean,
        val strokeColor: Color,
        val strokeWidth: Double,
        val shadowEnabled: Boolean,
        val shadowColor: Color,
        val shadowRadius: Double,
        val shadowOffset: Vec2,
        val backgroundEnabled: Boolean,
        val backgroundColor: Color,
        val backgroundPadding: Double,
        val backgroundRadius: Double,
        val maxWidth: Double
    ) : RenderContent

    data class Shape(
        val kind: ShapeKind,
        val size: Size,
        val cornerRadius: Double,
        val sides: Int,
        val fillEnabled: Boolean,
        val fillColor: Color,
        val strokeEnabled: Boolean,
        val strokeColor: Color,
        val strokeWidth: Double
    ) : RenderContent

    object Group : RenderContent
}

/** A mask in layer space, ready for the GPU mask pass. */
data class RenderMask(
    val id: String,
    val shape: MaskShape,
    val mode: MaskMode,
    val rect: Rect,
    val rotation: Double,
    val feather: Double,
    val opacity: Double,
    val invert: Boolean,
    val path: List<Vec2>
)

/**
 * One drawable layer of a frame.
 *
 * [matrix] maps layer content space (0,0 = top left of the content bitmap)
 * into canvas space, including the parent chain. A renderer only has to upload
 * the content, apply the matrix and the effect chain - no project knowledge.
 */
data class RenderNode(
    val layerId: String,
    val name: String,
    val type: LayerType,
    val content: RenderContent,
    val contentSize: Size,
    val matrix: Matrix3,
    val opacity: Double,
    val blendMode: BlendMode,
    val effects: List<ResolvedEffect>,
    val masks: List<RenderMask>,
    val assetId: String?,
    val sourceTime: Double,
    val volume: Double
)

/** Audio contributing to the frame at the playhead. */
data class ActiveAudio(
    val layerId: String,
    val assetId: String,
    val sourceTime: Double,
    val volume: Double,
    val muted: Boolean
)

/** A complete frame description produced by [CompositionEvaluator]. */
data class RenderPlan(
    val time: Double,
    val canvas: com.vynox.core.model.Canvas,
    val nodes: List<RenderNode>,
    val audio: List<ActiveAudio>
) {
    val background: Color get() = canvas.background
    val width: Int get() = canvas.width
    val height: Int get() = canvas.height
}

/** Timing helpers shared by preview and export. */
class TimelineClock(private val fps: Int) {
    val frameDuration: Double get() = 1.0 / fps
    fun timeToFrame(time: Double): Long = kotlin.math.floor(time * fps + 1e-6).toLong()
    fun frameToTime(frame: Long): Double = frame / fps.toDouble()
    fun snapToFrame(time: Double): Double = frameToTime(timeToFrame(time))
}
