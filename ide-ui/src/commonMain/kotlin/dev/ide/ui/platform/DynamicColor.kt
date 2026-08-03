package dev.ide.ui.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Material You dynamic color, derived from the device wallpaper. Returns a wallpaper-seeded [ColorScheme]
 * on Android 12+ (API 31); `null` everywhere else — desktop and pre-12 Android — where the theme falls back
 * to a fixed expressive palette seeded from the chosen accent. This is the "You" in Material You: the app's
 * primary/secondary/tertiary tones follow the user's home-screen colors.
 */
@Composable
expect fun dynamicColorSchemeOrNull(dark: Boolean): ColorScheme?
