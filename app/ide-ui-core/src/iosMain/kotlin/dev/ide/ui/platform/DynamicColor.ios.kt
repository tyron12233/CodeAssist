package dev.ide.ui.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// iOS exposes no wallpaper-derived palette (the system accent is not readable by an app), so the theme
// always uses the fixed expressive scheme seeded from the chosen accent, as desktop does.
@Composable
actual fun dynamicColorSchemeOrNull(dark: Boolean): ColorScheme? = null
