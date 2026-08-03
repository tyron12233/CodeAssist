package dev.ide.ui.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// Desktop has no wallpaper-derived palette; the theme always uses the fixed expressive scheme seeded from
// the chosen accent.
@Composable
actual fun dynamicColorSchemeOrNull(dark: Boolean): ColorScheme? = null
