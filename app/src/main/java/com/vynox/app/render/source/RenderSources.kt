package com.vynox.app.render.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vynox.app.render.gl.LayerTexture
import com.vynox.app.render.gl.RenderSourceProvider
import com.vynox.app.render.gl.TextureCache
import com.vynox.core.composition.RenderContent
import com.vynox.core.composition.RenderMask
import com.vynox.core.composition.RenderNode
import com.vynox.core.composition.TextMetricsProvider
import com.vynox.core.math.Size
import kotlin.math.max

/**
 * Bridges the project assets and the OpenGL compositor.
 *
 * Everything is cached: images and rasterized graphics are uploaded once, video
 * decoders are kept alive per asset and only seek when the requested time moves
 * outside the decoded window.
 */
class RenderSources(
    private val context: Context,
    private val assetUri: (String) -> String?
) : RenderSourceProvider {

    private val cache = TextureCache()
    private val videos = HashMap<String, VideoDecoder>()
    private val textCache = HashMap<String, Pair<LayerTexture, Size>>()
    private val maskCache = HashMap<String, LayerTexture>()
    private val oesTextures = ArrayList<Int>()

    var renderWidth: Int = 1920
    var renderHeight: Int = 1080

    /** Platform accurate text measurement so layout matches what is drawn. */
    val textMetrics: TextMetricsProvider = AndroidTextMetrics

    override fun videoTexture(assetId: String, sourceTime: Double): LayerTexture? {
        val uri = assetUri(assetId) ?: return null
        val decoder = videos.getOrPut(assetId) {
            val textureId = cache.createEmpty()
            oesTextures += textureId
            VideoDecoder(context, uri, textureId)
        }
        if (!decoder.isOpen) return null
        return decoder.frameAt(sourceTime)
    }

    override fun imageTexture(assetId: String): LayerTexture? {
        val cached = cache.get("img:$assetId")
        if (cached != null) return LayerTexture(cached, imageWidth(assetId), imageHeight(assetId))
        val uri = assetUri(assetId) ?: return null
        val bitmap = decodeSampled(uri, max(renderWidth, renderHeight)) ?: return null
        val textureId = cache.createFromBitmap(bitmap)
        cache.put("img:$assetId", textureId)
        imageSizes[assetId] = bitmap.width to bitmap.height
        bitmap.recycle()
        return LayerTexture(textureId, imageSizes[assetId]!!.first, imageSizes[assetId]!!.second)
    }

    private val imageSizes = HashMap<String, Pair<Int, Int>>()
    private fun imageWidth(assetId: String) = imageSizes[assetId]?.first ?: 1
    private fun imageHeight(assetId: String) = imageSizes[assetId]?.second ?: 1

    override fun textTexture(node: RenderNode): LayerTexture? {
        val content = node.content as? RenderContent.Text ?: return null
        val key = textKey(content)
        val cached = textCache[key]
        if (cached != null) return cached.first
        val raster = Rasterizers.rasterizeText(content, node.contentSize)
        val textureId = cache.createFromBitmap(raster.bitmap)
        val texture = LayerTexture(
            textureId = textureId,
            width = raster.bitmap.width,
            height = raster.bitmap.height,
            padLeft = raster.padLeft,
            padTop = raster.padTop
        )
        raster.bitmap.recycle()
        // Only the most recent few text layers are kept hot.
        if (textCache.size > 12) {
            val oldest = textCache.keys.first()
            textCache.remove(oldest)
        }
        textCache[key] = texture to node.contentSize
        return texture
    }

    override fun shapeTexture(node: RenderNode): LayerTexture? {
        val content = node.content as? RenderContent.Shape ?: return null
        val key = "shape:${content.kind}:${content.size.width}:${content.size.height}:${content.cornerRadius}:" +
            "${content.fillColor.argb}:${content.strokeColor.argb}:${content.strokeWidth}:${content.fillEnabled}:${content.strokeEnabled}:${content.sides}"
        val cached = cache.get(key)
        if (cached != null) {
            val raster = Rasterizers.rasterizeShape(content)
            return LayerTexture(cached, raster.bitmap.width, raster.bitmap.height, padLeft = raster.padLeft, padTop = raster.padTop).also {
                raster.bitmap.recycle()
            }
        }
        val raster = Rasterizers.rasterizeShape(content)
        val textureId = cache.createFromBitmap(raster.bitmap)
        cache.put(key, textureId)
        val texture = LayerTexture(
            textureId = textureId,
            width = raster.bitmap.width,
            height = raster.bitmap.height,
            padLeft = raster.padLeft,
            padTop = raster.padTop
        )
        raster.bitmap.recycle()
        return texture
    }

    override fun maskPathTexture(mask: RenderMask, scale: Float): LayerTexture? {
        val cached = maskCache[mask.id]
        if (cached != null) return cached
        if (mask.path.size < 2) return null
        val bitmap = Rasterizers.rasterizeMaskPath(mask.path, renderWidth, renderHeight, scale)
        val textureId = cache.createFromBitmap(bitmap)
        bitmap.recycle()
        val texture = LayerTexture(textureId, bitmap.width, bitmap.height)
        maskCache[mask.id] = texture
        return texture
    }

    override fun placeholder(): LayerTexture? {
        val cached = cache.get("__placeholder")
        if (cached != null) return LayerTexture(cached, 64, 64)
        val bitmap = Rasterizers.placeholderBitmap()
        val textureId = cache.createFromBitmap(bitmap)
        cache.put("__placeholder", textureId)
        bitmap.recycle()
        return LayerTexture(textureId, 64, 64)
    }

    private fun textKey(content: RenderContent.Text): String =
        listOf(
            content.text, content.fontFamily, content.fontSize, content.fontWeight, content.italic,
            content.alignment, content.letterSpacing, content.lineSpacing, content.color.argb,
            content.strokeEnabled, content.strokeColor.argb, content.strokeWidth,
            content.shadowEnabled, content.shadowColor.argb, content.shadowRadius,
            content.backgroundEnabled, content.backgroundColor.argb, content.maxWidth
        ).joinToString("|")

    private fun decodeSampled(uri: String, targetSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val maxDimension = max(options.outWidth, options.outHeight)
        var sample = 1
        while (maxDimension / (sample * 2) > targetSize && sample < 32) sample *= 2
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        return openStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    }

    private fun openStream(uri: String): java.io.InputStream? = try {
        when {
            uri.startsWith("content://") -> context.contentResolver.openInputStream(android.net.Uri.parse(uri))
            else -> java.io.FileInputStream(uri)
        }
    } catch (e: Exception) {
        null
    }

    fun invalidateText() {
        textCache.keys.toList().forEach { key ->
            textCache.remove(key)
        }
    }

    fun invalidate(assetId: String) {
        videos.remove(assetId)?.release()
        cache.get("img:$assetId")?.let { textureId ->
            android.opengl.GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    /** Called when a project is closed or the GL context is lost. */
    fun release() {
        videos.values.forEach { it.release() }
        videos.clear()
        textCache.clear()
        maskCache.clear()
        cache.clear()
        oesTextures.clear()
    }
}

/** Measures text with the platform text engine. */
object AndroidTextMetrics : TextMetricsProvider {
    override fun measure(
        text: String,
        fontFamily: String,
        fontSize: Double,
        fontWeight: Int,
        italic: Boolean,
        letterSpacing: Double,
        lineSpacing: Double,
        maxWidth: Double
    ): Size {
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize.toFloat().coerceAtLeast(1f)
            isFakeBoldText = fontWeight >= 600
            typeface = runCatching {
                android.graphics.Typeface.create(fontFamily, if (italic) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL)
            }.getOrDefault(android.graphics.Typeface.DEFAULT)
            this.letterSpacing = (letterSpacing / fontSize.coerceAtLeast(1.0)).toFloat()
        }
        val lines = text.split('\n')
        val widest = lines.maxOf { paint.measureText(it) }.toDouble()
        val lineHeight = fontSize * lineSpacing
        val limit = if (maxWidth > 0.0) maxWidth else Double.MAX_VALUE
        return Size(
            widest.coerceAtMost(limit),
            (lineHeight * lines.size).coerceAtLeast(lineHeight)
        )
    }
}
