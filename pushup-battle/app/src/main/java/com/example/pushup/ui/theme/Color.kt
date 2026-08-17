package com.example.pushup.ui.theme

import androidx.compose.ui.graphics.Color

// Base surfaces — near-black, gym-at-night feel
val BgDeep = Color(0xFF0B0D12)
val BgSurface = Color(0xFF15181F)
val BgSurfaceRaised = Color(0xFF1C2029)
val BgSurfaceBorder = Color(0xFF262B36)

// Text
val TextPrimary = Color(0xFFF4F5F7)
val TextMuted = Color(0xFF8B909C)
val TextFaint = Color(0xFF565B66)

// Accent — competitive orange (CTAs, "you")
val AccentOrange = Color(0xFFFF5A2E)
val AccentOrangeBright = Color(0xFFFF7A47)
val AccentOrangeDim = Color(0xFF4A2517)
val AccentOrangeGlow = Color(0x40FF5A2E)

// Accent — win green
val WinGreen = Color(0xFF3DDC84)
val WinGreenDim = Color(0xFF163829)

// Accent — lose red
val LoseRed = Color(0xFFFF4D5E)
val LoseRedDim = Color(0xFF3A1620)

// Rival blue (opponent)
val RivalBlue = Color(0xFF4E9BFF)

// Medal colors for leaderboard
val MedalGold = Color(0xFFFFC94D)
val MedalSilver = Color(0xFFD0D5DC)
val MedalBronze = Color(0xFFE0995E)

// Subtle top-edge sheen used on raised cards to fake a light source (adds depth
// without needing real shadows/blur, which keep minSdk 24 support simple).
val CardSheen = Color(0x14FFFFFF)
