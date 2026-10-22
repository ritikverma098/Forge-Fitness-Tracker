package com.forge.workout.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.forge.workout.preferences.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC4EF76), onPrimary = Color(0xFF25360C),
    primaryContainer = Color(0xFF31451B), onPrimaryContainer = Color(0xFFDDF9B1),
    secondary = Color(0xFFBFCBB1), secondaryContainer = Color(0xFF30392A),
    background = Color(0xFF111410), surface = Color(0xFF111410),
    surfaceContainerLowest = Color(0xFF0D100C), surfaceContainerLow = Color(0xFF191D17),
    surfaceContainer = Color(0xFF20241D), surfaceContainerHigh = Color(0xFF292D25),
    surfaceContainerHighest = Color(0xFF34382F), onSurface = Color(0xFFE5E9DD),
    onSurfaceVariant = Color(0xFFBBC2B2), outline = Color(0xFF858D7D)
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF476719), onPrimary = Color.White,
    primaryContainer = Color(0xFFC4EF76), onPrimaryContainer = Color(0xFF203009),
    secondary = Color(0xFF56624A), secondaryContainer = Color(0xFFE1EAD5),
    background = Color(0xFFF6F8F1), surface = Color(0xFFF6F8F1),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3E9),
    surfaceContainer = Color(0xFFEAEFE2), surfaceContainerHigh = Color(0xFFE3E8DB),
    surfaceContainerHighest = Color(0xFFDDE3D4), onSurface = Color(0xFF191E14),
    onSurfaceVariant = Color(0xFF535C49), outline = Color(0xFF76816A)
)
private val ForgeTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
)
@Composable fun ForgeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when(mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    MaterialTheme(colorScheme = if(dark) DarkColors else LightColors, typography = ForgeTypography, content = content)
}

