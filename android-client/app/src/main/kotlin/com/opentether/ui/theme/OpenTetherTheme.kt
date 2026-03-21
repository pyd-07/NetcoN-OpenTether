package com.opentether.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Color tokens ──────────────────────────────────────────────────────────────

val OtBackground  = Color(0xFF0D1117)
val OtSurface     = Color(0xFF161B22)
val OtSurfaceAlt  = Color(0xFF1C2128)
val OtOutline     = Color(0xFF30363D)
val OtBlue        = Color(0xFF58A6FF)
val OtBlueStrong  = Color(0xFF1F6FEB)
val OtGreen       = Color(0xFF2EA043)
val OtRed         = Color(0xFFF85149)
val OtYellow      = Color(0xFFD29922)
val OtText        = Color(0xFFE6EDF3)
val OtTextMuted   = Color(0xFF8B949E)

// ── Color scheme ──────────────────────────────────────────────────────────────

private val DarkColors = darkColorScheme(
    primary          = OtBlueStrong,
    secondary        = OtBlue,
    tertiary         = OtGreen,
    error            = OtRed,
    background       = OtBackground,
    surface          = OtSurface,
    surfaceVariant   = OtSurfaceAlt,
    outline          = OtOutline,
    onPrimary        = Color(0xFFF0F6FC),
    onSecondary      = OtText,
    onTertiary       = Color(0xFFF0F6FC),
    onBackground     = OtText,
    onSurface        = OtText,
    onSurfaceVariant = OtTextMuted,
    onError          = Color.White,
)

// ── Typography ────────────────────────────────────────────────────────────────
// Roboto (via FontFamily.SansSerif) is the system default on Android and is
// perfectly readable. The key improvement over the original is:
//   - Explicit letterSpacing on body and label styles (was missing = 0.sp)
//   - labelSmall added for timestamps and secondary metadata
//   - FontWeight defaults set here, reducing scattered fontWeight calls in UI

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.SemiBold,
        fontSize    = 28.sp,
        lineHeight  = 34.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.SemiBold,
        fontSize    = 20.sp,
        lineHeight  = 26.sp,
        letterSpacing = (-0.15).sp,
    ),
    titleMedium = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Medium,
        fontSize    = 16.sp,
        lineHeight  = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Normal,
        fontSize    = 15.sp,
        lineHeight  = 22.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Normal,
        fontSize    = 14.sp,
        lineHeight  = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Medium,
        fontSize    = 14.sp,
        lineHeight  = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    // Used for status-pill text, metric tile labels, chip labels
    labelMedium = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Medium,
        fontSize    = 12.sp,
        lineHeight  = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    // Used for log timestamps and secondary metadata lines
    labelSmall = TextStyle(
        fontFamily  = FontFamily.Monospace,
        fontWeight  = FontWeight.Normal,
        fontSize    = 11.sp,
        lineHeight  = 14.sp,
        letterSpacing = 0.3.sp,
    ),
)

// ── Shapes ────────────────────────────────────────────────────────────────────

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small      = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium     = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large      = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
)

// ── Theme entry point ─────────────────────────────────────────────────────────

@Composable
fun OpenTetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography  = AppTypography,
        shapes      = AppShapes,
        content     = content,
    )
}
