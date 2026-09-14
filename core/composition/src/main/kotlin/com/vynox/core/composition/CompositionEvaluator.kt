package com.vynox.core.composition

import com.vynox.core.effects.EffectRegistry
import com.vynox.core.effects.ResolvedEffect
import com.vynox.core.math.Matrix3
import com.vynox.core.math.Rect
import com.vynox.core.math.Size
import com.vynox.core.math.Vec2
import com.vynox.core.model.Asset
import com.vynox.core.model.AssetType
import com.vynox.core.model.Canvas
import com.vynox.core.model.Layer
import com.vynox.core.model.LayerContent
import com.vynox.core.model.LayerType
import com.vynox.core.model.VynoxProject
import com.vynox.core.model.audioContent
import com.vynox.core.model.imageContent
import com.vynox.core.model.shapeContent
import com.vynox.core.model.sourceTimeAt
import com.vynox.core.model.textContent
import com.vynox.core.model.videoContent
import com.vynox.core.model.volumeAt

/**
 * Turns a [VynoxProject] into a drawable [RenderPlan] for a point in time.
 *
 * This is the single source of truth for what a frame looks like: the on screen
 * preview and the export encoder both consume the output of this class, which
 * guarantees the exported file matches what the editor showed.
 */
class CompositionEvaluator(
    private val project: VynoxProject,
    private val textMetrics: TextMetricsProvider = ApproximateTextMetrics
) {

    val canvas: Canvas get() = project.canvas

    // ------------------------------------------------------------------ sizes

    /** Intrinsic content size of a layer in pixels (before transform). */
    fun contentSize(layer: Layer): Size {
        return when (val content = layer.content) {
            is LayerContent.VideoContent -> assetSize(content.asset) ?: Size(canvas.width.toDouble(), canvas.height.toDouble())
            is LayerContent.ImageContent -> assetSize(content.asset) ?: Size(canvas.width.toDouble(), canvas.height.toDouble())
            is LayerContent.TextContent -> content.measure(textMetrics)
            is LayerContent.ShapeContent -> Size(content.size.x, content.size.y)
            is LayerContent.AudioContent -> Size.ZERO
            is LayerContent.GroupContent -> Size.ZERO
        }
    }

    private fun assetSize(assetId: String): Size? {
        val asset: Asset = project.asset(assetId) ?: return null
        val w = asset.width
        val h = asset.height
        return if (w != null && h != null && w > 0 && h > 0) Size(w.toDouble(), h.toDouble()) else null
    }

    // ------------------------------------------------------------- transforms

    /** Transform of a layer including every ancestor (group/null) transform. */
    fun layerMatrix(layer: Layer, time: Double): Matrix3 {
        val chain = ancestors(layer) + layer
        var matrix = Matrix3.IDENTITY
        for (node in chain) {
            val size = contentSize(node)
            val nodeLocal = time - node.startTime
            val local = Matrix3.layerTransform(
                position = node.transform.positionAt(nodeLocal),
                anchor = node.transform.anchor,
                scale = node.transform.scaleAt(nodeLocal),
                rotationDegrees = node.transform.rotationAt(nodeLocal),
                contentSize = size
            )
            matrix = matrix.multiply(local)
        }
        return matrix
    }

    private fun ancestors(layer: Layer): List<Layer> {
        val result = ArrayList<Layer>()
        var current = layer.parentId?.let { project.layer(it) }
        var guard = 0
        while (current != null && guard++ < 32) {
            result.add(0, current)
            current = current.parentId?.let { project.layer(it) }
        }
        return result
    }

    /** Canvas space bounding box of a layer, used by transform handles. */
    fun layerBounds(layer: Layer, time: Double): Rect {
        val size = contentSize(layer)
        val matrix = layerMatrix(layer, time)
        val corners = listOf(
            matrix.transformPoint(Vec2(0.0, 0.0)),
            matrix.transformPoint(Vec2(size.width, 0.0)),
            matrix.transformPoint(Vec2(size.width, size.height)),
            matrix.transformPoint(Vec2(0.0, size.height))
        )
        val minX = corners.minOf { it.x }
        val minY = corners.minOf { it.y }
        val maxX = corners.maxOf { it.x }
        val maxY = corners.maxOf { it.y }
        return Rect(minX, minY, maxX - minX, maxY - minY)
    }

    /** Topmost layer whose content contains [point] at [time]. */
    fun hitTest(point: Vec2, time: Double): Layer? {
        for (layer in project.layers) {
            if (!isVisible(layer) || layer.locked) continue
            if (!layer.isActiveAt(time)) continue
            if (layer.type == LayerType.AUDIO || layer.type == LayerType.GROUP) {
                val size = contentSize(layer)
                if (size.width <= 0.0 || size.height <= 0.0) continue
            }
            val size = contentSize(layer)
            val matrix = layerMatrix(layer, time)
            val local = inverseTransform(matrix, point)
            if (local.x in 0.0..size.width && local.y in 0.0..size.height) return layer
        }
        return null
    }

    /** Inverse affine transform of a 3x3 matrix with the last row [0 0 1]. */
    private fun inverseTransform(matrix: Matrix3, point: Vec2): Vec2 {
        val det = matrix.m00 * matrix.m11 - matrix.m01 * matrix.m10
        if (kotlin.math.abs(det) < 1e-12) return Vec2(Double.NaN, Double.NaN)
        val x = point.x - matrix.m02
        val y = point.y - matrix.m12
        return Vec2(
            (x * matrix.m11 - y * matrix.m01) / det,
            (y * matrix.m00 - x * matrix.m10) / det
        )
    }

    // ------------------------------------------------------------ evaluation

    fun isVisible(layer: Layer): Boolean = layer.enabled

    /** Layers that contribute pixels at [time], bottom first. */
    fun activeLayers(time: Double): List<Layer> =
        project.drawOrder.filter { it.enabled && it.type != LayerType.AUDIO && it.isActiveAt(time) }

    fun evaluate(time: Double): RenderPlan {
        val nodes = activeLayers(time).mapNotNull { layer -> renderNode(layer, time) }
        return RenderPlan(time = time, canvas = project.canvas, nodes = nodes, audio = evaluateAudio(time))
    }

    fun renderNode(layer: Layer, time: Double): RenderNode? {
        val size = contentSize(layer)
        val localTime = time - layer.startTime
        val content = when (val c = layer.content) {
            is LayerContent.VideoContent -> RenderContent.Video(
                assetId = c.asset,
                sourceTime = layer.sourceTimeAt(time).coerceAtLeast(0.0),
                volume = layer.volumeAt(time),
                muted = c.muted || layer.muted
            )
            is LayerContent.ImageContent -> RenderContent.Image(assetId = c.asset, fit = c.fit)
            is LayerContent.TextContent -> RenderContent.Text(
                text = c.text,
                fontFamily = c.fontFamily,
                fontSize = c.fontSize,
                fontWeight = c.fontWeight,
                italic = c.italic,
                alignment = c.alignment,
                verticalAlign = c.verticalAlign,
                letterSpacing = c.letterSpacing,
                lineSpacing = c.lineSpacing,
                color = c.color.valueAt(localTime),
                strokeEnabled = c.strokeEnabled,
                strokeColor = c.strokeColor.valueAt(localTime),
                strokeWidth = c.strokeWidth,
                shadowEnabled = c.shadowEnabled,
                shadowColor = c.shadowColor.valueAt(localTime),
                shadowRadius = c.shadowRadius,
                shadowOffset = c.shadowOffset,
                backgroundEnabled = c.backgroundEnabled,
                backgroundColor = c.backgroundColor.valueAt(localTime),
                backgroundPadding = c.backgroundPadding,
                backgroundRadius = c.backgroundRadius,
                maxWidth = c.maxWidth
            )
            is LayerContent.ShapeContent -> RenderContent.Shape(
                kind = c.shape,
                size = Size(c.size.x, c.size.y),
                cornerRadius = c.cornerRadius,
                sides = c.sides,
                fillEnabled = c.fillEnabled,
                fillColor = c.fillColor.valueAt(localTime),
                strokeEnabled = c.strokeEnabled,
                strokeColor = c.strokeColor.valueAt(localTime),
                strokeWidth = c.strokeWidth
            )
            is LayerContent.AudioContent -> RenderContent.Group
            is LayerContent.GroupContent -> RenderContent.Group
        }

        // Groups / nulls / audio have nothing to draw.
        if (content is RenderContent.Group && layer.type != LayerType.GROUP) return null
        if (layer.type == LayerType.GROUP || layer.type == LayerType.AUDIO) return null

        val opacity = (layer.transform.opacityAt(localTime)).coerceIn(0.0, 1.0) *
            opacityFromEffects(layer, localTime)

        return RenderNode(
            layerId = layer.id,
            name = layer.name,
            type = layer.type,
            content = content,
            contentSize = size,
            matrix = layerMatrix(layer, time),
            opacity = opacity.coerceIn(0.0, 1.0),
            blendMode = layer.blendMode,
            effects = EffectRegistry.resolveAll(layer.effects, localTime).filter { it.enabled },
            masks = layer.masks.filter { it.enabled }.map { mask ->
                RenderMask(
                    id = mask.id,
                    shape = mask.shape,
                    mode = mask.mode,
                    rect = mask.rect,
                    rotation = mask.rotation,
                    feather = mask.feather,
                    opacity = mask.opacity,
                    invert = mask.invert,
                    path = mask.path
                )
            },
            assetId = layer.content.assetId,
            sourceTime = layer.sourceTimeAt(time).coerceAtLeast(0.0),
            volume = layer.volumeAt(time)
        )
    }

    private fun opacityFromEffects(layer: Layer, time: Double): Double {
        var factor = 1.0
        layer.effects.filter { it.enabled && it.typeId.endsWith(".opacity") }.forEach { effect ->
            val resolved: ResolvedEffect = EffectRegistry.resolve(effect, time)
            factor *= resolved.double("opacity", 1.0).coerceIn(0.0, 1.0)
        }
        return factor
    }

    /** Audio layers (and audio of video layers) active at [time]. */
    fun evaluateAudio(time: Double): List<ActiveAudio> {
        val anySolo = project.layers.any { it.solo }
        return project.layers.filter { layer ->
            val hasAudio = layer.type == LayerType.AUDIO || (layer.type == LayerType.VIDEO && !(layer.videoContent()?.muted ?: true))
            hasAudio && layer.enabled && layer.isActiveAt(time) && (!anySolo || layer.solo)
        }.mapNotNull { layer ->
            val assetId = when (val c = layer.content) {
                is LayerContent.AudioContent -> c.asset
                is LayerContent.VideoContent -> c.asset
                else -> return@mapNotNull null
            }
            ActiveAudio(
                layerId = layer.id,
                assetId = assetId,
                sourceTime = layer.sourceTimeAt(time).coerceAtLeast(0.0),
                volume = layer.volumeAt(time),
                muted = layer.muted || (layer.audioContent()?.muted ?: false) || (layer.videoContent()?.muted ?: false)
            )
        }
    }

    /** Assets referenced by the project at [time] (used to preload decoders). */
    fun assetsAt(time: Double): Set<String> =
        activeLayers(time).mapNotNull { it.content.assetId }.toSet()

    fun audioAssets(): Set<String> = project.assets.filter { it.type == AssetType.AUDIO || it.type == AssetType.VIDEO }
        .map { it.id }.toSet()
}
