package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.domain.model.ThemeMode

@Immutable
data class BSubThemeController(
  val themeMode: ThemeMode = ThemeMode.DARK,
  val isDarkTheme: Boolean = true,
  val onSetThemeMode: (ThemeMode) -> Unit = {},
  val onToggleLightDark: () -> Unit = {},
)

val LocalThemeController = compositionLocalOf { BSubThemeController() }

@Composable
fun ThemeProvider(
  themeMode: ThemeMode = ThemeMode.DARK,
  onThemeModeChange: (ThemeMode) -> Unit = {},
  animateTransitions: Boolean = true,
  content: @Composable () -> Unit,
) {
  val systemDark = isSystemInDarkTheme()
  val isDarkTheme =
    when (themeMode) {
      ThemeMode.DARK -> true
      ThemeMode.LIGHT -> false
      ThemeMode.SYSTEM -> systemDark
    }

  val targetScheme = if (isDarkTheme) BSubDarkColorScheme else BSubLightColorScheme
  val colorScheme =
    if (animateTransitions) {
      rememberAnimatedColorScheme(targetScheme)
    } else {
      targetScheme
    }

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val activity = view.context.findActivity()
      if (activity != null) {
        val windowInsetsController = WindowCompat.getInsetsController(activity.window, view)
        windowInsetsController.isAppearanceLightStatusBars = !isDarkTheme
        windowInsetsController.isAppearanceLightNavigationBars = !isDarkTheme
      }
    }
  }

  val controller =
    remember(themeMode, isDarkTheme, onThemeModeChange) {
      BSubThemeController(
        themeMode = themeMode,
        isDarkTheme = isDarkTheme,
        onSetThemeMode = onThemeModeChange,
        onToggleLightDark = {
          onThemeModeChange(if (isDarkTheme) ThemeMode.LIGHT else ThemeMode.DARK)
        },
      )
    }

  CompositionLocalProvider(LocalThemeController provides controller) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = BSubTypography,
      shapes = BSubShapes,
      content = content,
    )
  }
}

@Composable
private fun rememberAnimatedColorScheme(target: ColorScheme): ColorScheme {
  val animSpec = tween<Color>(durationMillis = 220)
  val primary by animateColorAsState(target.primary, animSpec, label = "theme_primary")
  val onPrimary by animateColorAsState(target.onPrimary, animSpec, label = "theme_onPrimary")
  val primaryContainer by
    animateColorAsState(target.primaryContainer, animSpec, label = "theme_primaryContainer")
  val onPrimaryContainer by
    animateColorAsState(target.onPrimaryContainer, animSpec, label = "theme_onPrimaryContainer")
  val secondary by animateColorAsState(target.secondary, animSpec, label = "theme_secondary")
  val onSecondary by animateColorAsState(target.onSecondary, animSpec, label = "theme_onSecondary")
  val secondaryContainer by
    animateColorAsState(target.secondaryContainer, animSpec, label = "theme_secondaryContainer")
  val onSecondaryContainer by
    animateColorAsState(
      target.onSecondaryContainer,
      animSpec,
      label = "theme_onSecondaryContainer",
    )
  val tertiary by animateColorAsState(target.tertiary, animSpec, label = "theme_tertiary")
  val onTertiary by animateColorAsState(target.onTertiary, animSpec, label = "theme_onTertiary")
  val tertiaryContainer by
    animateColorAsState(target.tertiaryContainer, animSpec, label = "theme_tertiaryContainer")
  val onTertiaryContainer by
    animateColorAsState(
      target.onTertiaryContainer,
      animSpec,
      label = "theme_onTertiaryContainer",
    )
  val background by animateColorAsState(target.background, animSpec, label = "theme_background")
  val onBackground by
    animateColorAsState(target.onBackground, animSpec, label = "theme_onBackground")
  val surface by animateColorAsState(target.surface, animSpec, label = "theme_surface")
  val onSurface by animateColorAsState(target.onSurface, animSpec, label = "theme_onSurface")
  val surfaceVariant by
    animateColorAsState(target.surfaceVariant, animSpec, label = "theme_surfaceVariant")
  val onSurfaceVariant by
    animateColorAsState(target.onSurfaceVariant, animSpec, label = "theme_onSurfaceVariant")
  val surfaceContainerLowest by
    animateColorAsState(
      target.surfaceContainerLowest,
      animSpec,
      label = "theme_surfaceContainerLowest",
    )
  val surfaceContainerLow by
    animateColorAsState(
      target.surfaceContainerLow,
      animSpec,
      label = "theme_surfaceContainerLow",
    )
  val surfaceContainer by
    animateColorAsState(target.surfaceContainer, animSpec, label = "theme_surfaceContainer")
  val surfaceContainerHigh by
    animateColorAsState(
      target.surfaceContainerHigh,
      animSpec,
      label = "theme_surfaceContainerHigh",
    )
  val surfaceContainerHighest by
    animateColorAsState(
      target.surfaceContainerHighest,
      animSpec,
      label = "theme_surfaceContainerHighest",
    )
  val outline by animateColorAsState(target.outline, animSpec, label = "theme_outline")
  val outlineVariant by
    animateColorAsState(target.outlineVariant, animSpec, label = "theme_outlineVariant")
  val error by animateColorAsState(target.error, animSpec, label = "theme_error")
  val onError by animateColorAsState(target.onError, animSpec, label = "theme_onError")
  val errorContainer by
    animateColorAsState(target.errorContainer, animSpec, label = "theme_errorContainer")
  val onErrorContainer by
    animateColorAsState(target.onErrorContainer, animSpec, label = "theme_onErrorContainer")

  return target.copy(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceContainerLowest = surfaceContainerLowest,
    surfaceContainerLow = surfaceContainerLow,
    surfaceContainer = surfaceContainer,
    surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest,
    outline = outline,
    outlineVariant = outlineVariant,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
  )
}

private tailrec fun Context.findActivity(): Activity? {
  return when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }
}
