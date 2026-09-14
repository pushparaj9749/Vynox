package com.vynox.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Vynox design language.
 *
 * A dark, low-chroma workspace so timeline colour coding and video pixels stay
 * the brightest thing on screen, with violet/cyan accents reserved for
 * interactive state.
 */
object VynoxColors {
    val Ink = Color(0xFF08090D)
    val InkRaised = Color(0xFF0E1016)
    val Surface = Color(0xFF12151D)
    val SurfaceRaised = Color(0xFF181C26)
    val SurfaceHighest = Color(0xFF1F2430)
    val Outline = Color(0xFF262B38)
    val OutlineStrong = Color(0xFF333A4B)

    val Violet = Color(0xFF6C5CE7)
    val VioletSoft = Color(0xFF8B7BFF)
    val Cyan = Color(0xFF22D3EE)
    val Pink = Color(0xFFF472B6)
    val Amber = Color(0xFFFBBF24)
    val Emerald = Color(0xFF34D399)
    val Rose = Color(0xFFF87171)

    val TextPrimary = Color(0xFFF3F5FA)
    val TextSecondary = Color(0xFFA7B0C0)
    val TextMuted = Color(0xFF6B7488)

    // Layer label palette (mirrors core model LayerLabel)
    val LabelRed = Color(0xFFEF5350)
    val LabelOrange = Color(0xFFFFA726)
    val LabelYellow = Color(0xFFFFEE58)
    val LabelGreen = Color(0xFF66BB6A)
    val LabelBlue = Color(0xFF42A5F5)
    val LabelPurple = Color(0xFFAB47BC)
    val LabelGrey = Color(0xFFBDBDBD)

    val TimelineClip = Color(0xFF2A3346)
    val TimelineClipVideo = Color(0xFF3B4A6B)
    val TimelineClipAudio = Color(0xFF2F5D4E)
    val TimelineClipText = Color(0xFF5B4A7A)
    val TimelineClipShape = Color(0xFF6B4A2F)
    val Playhead = Color(0xFFFF3B5C)
}

/** Extra colours that are not part of Material3's colour scheme. */
data class VynoxExtendedColors(
    val surfaceRaised: Color = VynoxColors.SurfaceRaised,
    val surfaceHighest: Color = VynoxColors.SurfaceHighest,
    val outline: Color = VynoxColors.Outline,
    val outlineStrong: Color = VynoxColors.OutlineStrong,
    val accent: Color = VynoxColors.Violet,
    val accentAlt: Color = VynoxColors.Cyan,
    val success: Color = VynoxColors.Emerald,
    val warning: Color = VynoxColors.Amber,
    val danger: Color = VynoxColors.Rose,
    val textMuted: Color = VynoxColors.TextMuted,
    val playhead: Color = VynoxColors.Playhead
)

val LocalVynoxColors = staticCompositionLocalOf { VynoxExtendedColors() }

private val VynoxColorScheme = darkColorScheme(
    primary = VynoxColors.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF241E4D),
    onPrimaryContainer = VynoxColors.VioletSoft,
    secondary = VynoxColors.Cyan,
    onSecondary = Color(0xFF04222B),
    secondaryContainer = Color(0xFF0E3A44),
    onSecondaryContainer = VynoxColors.Cyan,
    tertiary = VynoxColors.Pink,
    onTertiary = Color(0xFF3A0F24),
    background = VynoxColors.Ink,
    onBackground = VynoxColors.TextPrimary,
    surface = VynoxColors.Surface,
    onSurface = VynoxColors.TextPrimary,
    surfaceVariant = VynoxColors.SurfaceRaised,
    onSurfaceVariant = VynoxColors.TextSecondary,
    surfaceTint = VynoxColors.Violet,
    outline = VynoxColors.Outline,
    outlineVariant = VynoxColors.OutlineStrong,
    error = VynoxColors.Rose,
    onError = Color.White
)

val VynoxTypography = Typography()

@Composable
fun VynoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) VynoxColorScheme else VynoxColorScheme
    CompositionLocalProvider(LocalVynoxColors provides VynoxExtendedColors()) {
        MaterialTheme(
            colorScheme = colors,
            typography = VynoxTypography,
            shapes = VynoxShapes,
            content = content
        )
    }
}

/** Convenience accessor for the extended palette. */
val MaterialTheme.extra: VynoxExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVynoxColors.current
