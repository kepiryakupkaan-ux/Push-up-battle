package com.example.pushup.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val PushUpColorScheme = darkColorScheme(
    primary = AccentOrange,
    onPrimary = TextPrimary,
    secondary = RivalBlue,
    background = BgDeep,
    onBackground = TextPrimary,
    surface = BgSurface,
    onSurface = TextPrimary,
    surfaceVariant = BgSurfaceRaised,
    onSurfaceVariant = TextMuted,
    outline = BgSurfaceBorder,
    error = LoseRed,
)

private val PushUpTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.2.sp),
)

@Composable
fun PushUpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PushUpColorScheme,
        typography = PushUpTypography,
        content = content
    )
}
