package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.domain.model.ThemeMode

internal val BSubDarkColorScheme =
  darkColorScheme(
    primary = CloverMintAqua,
    onPrimary = Color(0xFF002922),
    primaryContainer = CloverEmeraldContainerDark,
    onPrimaryContainer = Color(0xFFC5F4E9),
    secondary = CloverYellowDarkAccent,
    onSecondary = Color(0xFF2B2000),
    secondaryContainer = CloverYellowContainerDark,
    onSecondaryContainer = Color(0xFFFFEEAD),
    tertiary = CloverMintSuccessDark,
    onTertiary = Color(0xFF002820),
    tertiaryContainer = CloverMintContainerDark,
    onTertiaryContainer = Color(0xFFC4F7E9),
    background = StudioMidnight,
    onBackground = TextPrimaryDark,
    surface = StudioSlate,
    onSurface = TextPrimaryDark,
    surfaceVariant = StudioCard,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = StudioMidnight,
    surfaceContainerLow = StudioAbyss,
    surfaceContainer = StudioSlate,
    surfaceContainerHigh = StudioCard,
    surfaceContainerHighest = StudioCardElevated,
    outline = StudioBorder,
    outlineVariant = StudioBorderSubtle,
    error = CloverCoral,
    onError = Color(0xFF3F0B07),
    errorContainer = CloverCoralContainerDark,
    onErrorContainer = Color(0xFFFFD8D3),
  )

internal val BSubLightColorScheme =
  lightColorScheme(
    primary = CloverEmerald,
    onPrimary = Color.White,
    primaryContainer = CloverMintSoft,
    onPrimaryContainer = CloverMintInk,
    secondary = CloverYellowLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = CloverYellowTint,
    onSecondaryContainer = CloverYellowInk,
    tertiary = CloverEmeraldLightTertiary,
    onTertiary = Color.White,
    tertiaryContainer = CloverMintSoft,
    onTertiaryContainer = CloverMintInk,
    background = LightCanvas,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = LightSurfaceLowest,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = LightSurface,
    surfaceContainerHighest = LightSurfaceHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = CloverCoralCrimson,
    onError = Color.White,
    errorContainer = CloverCoralTint,
    onErrorContainer = CloverCoralInk,
  )

/**
 * The "petal": three round corners and one sharp corner, like the icon's teardrops.
 * Use it only on the Studio header card and on main action buttons.
 */
object BSubShape {
  val petal = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 6.dp)
  val petalButtonLarge = RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp, bottomEnd = 27.dp, bottomStart = 6.dp)
  val petalButton = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 5.dp)
}

val BSubShapes =
  Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun BSubTranslatorTheme(
  themeMode: ThemeMode = ThemeMode.DARK,
  onThemeModeChange: (ThemeMode) -> Unit = {},
  animateTransitions: Boolean = true,
  content: @Composable () -> Unit,
) {
  ThemeProvider(
    themeMode = themeMode,
    onThemeModeChange = onThemeModeChange,
    animateTransitions = animateTransitions,
    content = content,
  )
}
