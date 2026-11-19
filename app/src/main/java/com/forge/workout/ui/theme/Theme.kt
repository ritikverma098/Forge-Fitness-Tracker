package com.forge.workout.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.workout.preferences.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC8FF36), onPrimary = Color(0xFF162000),
    primaryContainer = Color(0xFF2D3C17), onPrimaryContainer = Color(0xFFDFFF9A),
    inversePrimary = Color(0xFF405B00),
    secondary = Color(0xFFBCCCAB), onSecondary = Color(0xFF253019),
    secondaryContainer = Color(0xFF323B2F), onSecondaryContainer = Color(0xFFDCE8D5),
    tertiary = Color(0xFFFFCF87), onTertiary = Color(0xFF402D0B),
    tertiaryContainer = Color(0xFF4A3718), onTertiaryContainer = Color(0xFFFFDEA9),
    error = Color(0xFFFFB4AC), onError = Color(0xFF5C1814),
    errorContainer = Color(0xFF502522), onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF101310), onBackground = Color(0xFFF4F6F1),
    surface = Color(0xFF101310), onSurface = Color(0xFFF4F6F1),
    surfaceVariant = Color(0xFF303730), onSurfaceVariant = Color(0xFFBEC6BC),
    surfaceContainerLowest = Color(0xFF0C0F0C), surfaceContainerLow = Color(0xFF1B201C),
    surfaceContainer = Color(0xFF222823), surfaceContainerHigh = Color(0xFF292E29),
    surfaceContainerHighest = Color(0xFF343B34), outline = Color(0xFF8D988A),
    outlineVariant = Color(0xFF3A433A),
    inverseSurface = Color(0xFFE5EAE2), inverseOnSurface = Color(0xFF262D25),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF385400), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F6A9), onPrimaryContainer = Color(0xFF233600),
    inversePrimary = Color(0xFFC8FF36),
    secondary = Color(0xFF4F6044), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1EAD8), onSecondaryContainer = Color(0xFF273421),
    tertiary = Color(0xFF805417), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE4BC), onTertiaryContainer = Color(0xFF4A3109),
    error = Color(0xFFA42A22), onError = Color.White,
    errorContainer = Color(0xFFFFDDD8), onErrorContainer = Color(0xFF631A14),
    background = Color(0xFFF6F8F3), onBackground = Color(0xFF181E16),
    surface = Color(0xFFF6F8F3), onSurface = Color(0xFF181E16),
    surfaceVariant = Color(0xFFE0E7D9), onSurfaceVariant = Color(0xFF4E5949),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F4EC),
    surfaceContainer = Color(0xFFE8EDE4), surfaceContainerHigh = Color(0xFFE3E9DD),
    surfaceContainerHighest = Color(0xFFDAE1D5), outline = Color(0xFF697463),
    outlineVariant = Color(0xFFC6CEC0),
    inverseSurface = Color(0xFF2B3329), inverseOnSurface = Color(0xFFF0F4EC),
)

private fun type(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp,
)

private val ForgeTypography = Typography(
    displaySmall = type(36, 42, FontWeight.Bold, -.9),
    headlineLarge = type(30, 36, FontWeight.Bold, -.7),
    headlineMedium = type(26, 32, FontWeight.Bold, -.4),
    headlineSmall = type(23, 30, FontWeight.SemiBold, -.3),
    titleLarge = type(21, 27, FontWeight.SemiBold, -.2),
    titleMedium = type(16, 22, FontWeight.SemiBold),
    titleSmall = type(14, 20, FontWeight.SemiBold),
    bodyLarge = type(16, 24, FontWeight.Normal),
    bodyMedium = type(14, 20, FontWeight.Normal),
    bodySmall = type(12, 18, FontWeight.Normal, .1),
    labelLarge = type(14, 20, FontWeight.SemiBold),
    labelMedium = type(12, 16, FontWeight.SemiBold, .2),
    labelSmall = type(11, 16, FontWeight.Medium, .2),
)

private val ForgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun ForgeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ForgeTypography,
        shapes = ForgeShapes,
        content = content,
    )
}
