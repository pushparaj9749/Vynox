package com.vynox.core.composition

import com.vynox.core.math.Size
import com.vynox.core.model.LayerContent
import kotlin.math.ceil

/**
 * Text measurement abstraction.
 *
 * Accurate glyph metrics require a platform text engine; the engine therefore
 * takes a provider (Android supplies a Paint based one) and falls back to a
 * decent approximation so the pure JVM engine stays testable and layout sane
 * even before the platform provider is attached.
 */
interface TextMetricsProvider {
    fun measure(
        text: String,
        fontFamily: String,
        fontSize: Double,
        fontWeight: Int,
        italic: Boolean,
        letterSpacing: Double,
        lineSpacing: Double,
        maxWidth: Double
    ): Size
}

object ApproximateTextMetrics : TextMetricsProvider {
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
        if (text.isEmpty()) return Size(fontSize, fontSize * lineSpacing)
        val weightFactor = 0.5 + (fontWeight.coerceIn(100, 900) - 400) / 3000.0
        val lines = text.split('\n')
        val em = fontSize
        var widest = 0.0
        lines.forEach { line ->
            val width = line.sumOf { ch -> charWidth(ch, em, weightFactor) } + letterSpacing * (line.length - 1).coerceAtLeast(0)
            if (width > widest) widest = width
        }
        if (maxWidth > 0.0 && widest > maxWidth) widest = maxWidth
        val height = ceil(lines.size * em * lineSpacing)
        return Size(widest + em * 0.08, height)
    }

    private fun charWidth(ch: Char, em: Double, weightFactor: Double): Double {
        val base = when {
            ch == ' ' -> 0.28
            ch in 'a'..'z' -> if (ch in "iljtf") 0.32 else if (ch in "m w".toSet()) 0.88 else 0.55
            ch in 'A'..'Z' -> if (ch == 'I') 0.32 else if (ch == 'M' || ch == 'W') 0.92 else 0.68
            ch in '0'..'9' -> 0.56
            ch == '.' || ch == ',' -> 0.28
            else -> 0.6
        }
        return base * em * (0.9 + weightFactor * 0.2)
    }
}

fun LayerContent.TextContent.measure(provider: TextMetricsProvider): Size = provider.measure(
    text = text,
    fontFamily = fontFamily,
    fontSize = fontSize,
    fontWeight = fontWeight,
    italic = italic,
    letterSpacing = letterSpacing,
    lineSpacing = lineSpacing,
    maxWidth = maxWidth
)
