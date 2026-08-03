package dev.ide.ui.platform

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Wallpaper-derived dynamic color is a platform feature of Android 12 (API 31) and up; below that the theme
// uses the fixed expressive palette. `dynamicDark/LightColorScheme` read the system's generated tonal
// palette off the current context.
@Composable
actual fun dynamicColorSchemeOrNull(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}
