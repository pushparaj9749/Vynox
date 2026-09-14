package com.vynox.app.render.source

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.vynox.core.composition.RenderContent
import com.vynox.core.math.Size
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class RasterResult(val bitmap: Bitmap, val padLeft: Float, val padTop: Float)

/**
 * Rasterizes vector content (text, shapes, mask paths) into bitmaps.
 *
 * Text and shapes are drawn once and cached; only the composited result is
 * recomputed per frame, which keeps the preview at interactive frame rates even
 * with many layers.
 */
object Rasterizers {

    fun rasterizeText(content: RenderContent.Text, measured: Size): RasterResult {
        val fontSize = content.fontSize.toFloat().coerceAtLeast(1f)
        val strokePad = if (content.strokeEnabled) content.strokeWidth.toFloat() / 2f + 1f else 0f
        val shadowPad = if (content.shadowEnabled) {
            content.shadowRadius.toFloat() + kotlin.math.abs(content.shadowOffset.y.toFloat()) + 2f
        } else 0f
        val pad = ceil(maxOf(strokePad, shadowPad, 2f)).toInt().coerceAtLeast(2)
        val padLeftExtra = if (content.shadowEnabled) ceil(kotlin.math.abs(content.shadowOffset.x.toFloat())).toInt() else 0

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            color = Color.argb(
                (content.color.a * 255).toInt(),
                (content.color.r * 255).toInt(),
                (content.color.g * 255).toInt(),
                (content.color.b * 255).toInt()
            )
            isFakeBoldText = content.fontWeight >= 600
            textSkewX = if (content.italic) -0.2f else 0f
            letterSpacing = (content.letterSpacing / content.fontSize).toFloat()
        }
        val typeface = safeTypeface(content.fontFamily, content.fontWeight, content.italic)
        paint.typeface = typeface

        val maxWidth = if (content.maxWidth > 0) content.maxWidth.toFloat().coerceAtLeast(fontSize) else 100000f
        val layout = buildLayout(content, paint, maxWidth)
        val textWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
        val width = ceil(max(textWidth, measured.width.toFloat())).toInt().coerceAtLeast(1) + pad * 2 + padLeftExtra
        val height = ceil(max(layout.height.toFloat(), measured.height.toFloat())).toInt().coerceAtLeast(1) + pad * 2

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (content.backgroundEnabled) {
            val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(
                    (content.backgroundColor.a * 255).toInt(),
                    (content.backgroundColor.r * 255).toInt(),
                    (content.backgroundColor.g * 255).toInt(),
                    (content.backgroundColor.b * 255).toInt()
                )
                style = Paint.Style.FILL
            }
            val rect = RectF(
                0f,
                0f,
                width.toFloat(),
                height.toFloat()
            )
            canvas.drawRoundRect(rect, content.backgroundRadius.toFloat(), content.backgroundRadius.toFloat(), backgroundPaint)
        }

        canvas.save()
        canvas.translate((pad + padLeftExtra).toFloat(), pad.toFloat())

        if (content.shadowEnabled) {
            paint.setShadowLayer(
                content.shadowRadius.toFloat(),
                content.shadowOffset.x.toFloat(),
                content.shadowOffset.y.toFloat(),
                android.graphics.Color.argb(
                    (content.shadowColor.a * 255).toInt(),
                    (content.shadowColor.r * 255).toInt(),
                    (content.shadowColor.g * 255).toInt(),
                    (content.shadowColor.b * 255).toInt()
                )
            )
        } else {
            paint.clearShadowLayer()
        }
        layout.draw(canvas)

        if (content.strokeEnabled) {
            val strokePaint = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = content.strokeWidth.toFloat()
                strokeJoin = Paint.Join.ROUND
                color = android.graphics.Color.argb(
                    (content.strokeColor.a * 255).toInt(),
                    (content.strokeColor.r * 255).toInt(),
                    (content.strokeColor.g * 255).toInt(),
                    (content.strokeColor.b * 255).toInt()
                )
                clearShadowLayer()
            }
            buildLayout(content, strokePaint, maxWidth).draw(canvas)
        }
        canvas.restore()
        return RasterResult(bitmap, (pad + padLeftExtra).toFloat(), pad.toFloat())
    }

    private fun buildLayout(content: RenderContent.Text, paint: TextPaint, maxWidth: Float): StaticLayout {
        val alignment = when (content.alignment) {
            com.vynox.core.model.TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            com.vynox.core.model.TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            com.vynox.core.model.TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(content.text, 0, content.text.length, paint, maxWidth.toInt())
                .setAlignment(alignment)
                .setLineSpacing(0f, content.lineSpacing.toFloat())
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                content.text, paint, maxWidth.toInt(), alignment,
                content.lineSpacing.toFloat(), 0f, false
            )
        }
    }

    private fun safeTypeface(family: String, weight: Int, italic: Boolean): Typeface {
        val base = runCatching {
            val style = if (italic) Typeface.ITALIC else Typeface.NORMAL
            val candidate = Typeface.create(family, style)
            if (candidate != Typeface.DEFAULT) candidate else Typeface.defaultFromStyle(style)
        }.getOrDefault(Typeface.DEFAULT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight.coerceIn(100, 900), italic)
        } else {
            base
        }
    }

    fun rasterizeShape(content: RenderContent.Shape): RasterResult {
        val strokePad = if (content.strokeEnabled) content.strokeWidth.toFloat() / 2f + 1f else 0f
        val pad = ceil(strokePad).toInt().coerceAtLeast(1)
        val width = ceil(content.size.width).toInt().coerceAtLeast(1) + pad * 2
        val height = ceil(content.size.height).toInt().coerceAtLeast(1) + pad * 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.argb(
                (content.fillColor.a * 255).toInt(),
                (content.fillColor.r * 255).toInt(),
                (content.fillColor.g * 255).toInt(),
                (content.fillColor.b * 255).toInt()
            )
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = content.strokeWidth.toFloat()
            strokeJoin = Paint.Join.ROUND
            color = android.graphics.Color.argb(
                (content.strokeColor.a * 255).toInt(),
                (content.strokeColor.r * 255).toInt(),
                (content.strokeColor.g * 255).toInt(),
                (content.strokeColor.b * 255).toInt()
            )
        }

        val rect = RectF(
            pad.toFloat(),
            pad.toFloat(),
            (width - pad).toFloat(),
            (height - pad).toFloat()
        )
        val path = when (content.kind) {
            com.vynox.core.model.ShapeKind.RECT, com.vynox.core.model.ShapeKind.ROUNDED_RECT -> {
                val radius = if (content.kind == com.vynox.core.model.ShapeKind.ROUNDED_RECT) content.cornerRadius.toFloat() else 0f
                val p = Path()
                p.addRoundRect(rect, radius, radius, Path.Direction.CW)
                p
            }
            com.vynox.core.model.ShapeKind.ELLIPSE -> {
                val p = Path()
                p.addOval(rect, Path.Direction.CW)
                p
            }
            com.vynox.core.model.ShapeKind.LINE -> {
                val p = Path()
                p.moveTo(rect.left, rect.centerY())
                p.lineTo(rect.right, rect.centerY())
                p
            }
            com.vynox.core.model.ShapeKind.POLYGON -> polygonPath(rect, content.sides.coerceAtLeast(3))
        }

        if (content.fillEnabled) canvas.drawPath(path, fillPaint)
        if (content.strokeEnabled) canvas.drawPath(path, strokePaint)
        return RasterResult(bitmap, pad.toFloat(), pad.toFloat())
    }

    private fun polygonPath(rect: RectF, sides: Int): Path {
        val path = Path()
        val cx = rect.centerX()
        val cy = rect.centerY()
        val rx = rect.width() / 2f
        val ry = rect.height() / 2f
        for (i in 0 until sides) {
            val angle = (Math.PI * 2.0 * i / sides) - Math.PI / 2.0
            val x = (cx + cos(angle) * rx).toFloat()
            val y = (cy + sin(angle) * ry).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** Rasterizes a free form mask path into a white silhouette. */
    fun rasterizeMaskPath(points: List<com.vynox.core.math.Vec2>, width: Int, height: Int, scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (points.size < 2) return bitmap
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val path = Path()
        path.moveTo((points[0].x * scale).toFloat(), (points[0].y * scale).toFloat())
        for (i in 1 until points.size) {
            path.lineTo((points[i].x * scale).toFloat(), (points[i].y * scale).toFloat())
        }
        path.close()
        canvas.drawPath(path, paint)
        return bitmap
    }

    /** Small neutral placeholder shown when media is missing. */
    fun placeholderBitmap(size: Int = 64): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.argb(60, 255, 255, 255))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(size * 0.25f, size * 0.25f, size * 0.75f, size * 0.75f, paint)
        canvas.drawLine(size * 0.75f, size * 0.25f, size * 0.25f, size * 0.75f, paint)
        return bitmap
    }
}
