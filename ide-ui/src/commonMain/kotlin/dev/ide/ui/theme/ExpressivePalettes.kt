package dev.ide.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Material 3 Expressive tonal palettes. These are the fixed fallback schemes used on desktop and on
 * Android below 12 (where wallpaper-derived dynamic color isn't available — see
 * [dev.ide.ui.platform.dynamicColorSchemeOrNull]). One neutral surface ramp is shared across the three
 * accents; each accent layers its own vibrant primary / secondary / tertiary tones on top. Chroma is kept
 * high per the expressive spec — the tertiary role in particular carries a colorful counter-accent.
 */

/** The accent roles that differ per seed. Neutral surfaces are shared. */
private class AccentRoles(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
)

private fun ColorScheme.withAccent(a: AccentRoles): ColorScheme = copy(
    primary = a.primary, onPrimary = a.onPrimary,
    primaryContainer = a.primaryContainer, onPrimaryContainer = a.onPrimaryContainer,
    inversePrimary = a.inversePrimary,
    secondary = a.secondary, onSecondary = a.onSecondary,
    secondaryContainer = a.secondaryContainer, onSecondaryContainer = a.onSecondaryContainer,
    tertiary = a.tertiary, onTertiary = a.onTertiary,
    tertiaryContainer = a.tertiaryContainer, onTertiaryContainer = a.onTertiaryContainer,
    surfaceTint = a.primary,
)

// ---- Shared neutral surface ramps (the M3 surface-container tiers) ----

private val NeutralLight = lightColorScheme(
    background = Color(0xFFFAF9FD),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceTint = Color(0xFF6E4CE0),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F0F4),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAF9FD),
    surfaceDim = Color(0xFFDAD9DE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3F7),
    surfaceContainer = Color(0xFFEEEDF2),
    surfaceContainerHigh = Color(0xFFE9E7EC),
    surfaceContainerHighest = Color(0xFFE3E2E6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val NeutralDark = darkColorScheme(
    background = Color(0xFF131316),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceTint = Color(0xFFCFBCFF),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303034),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39393D),
    surfaceDim = Color(0xFF131316),
    surfaceContainerLowest = Color(0xFF0E0E11),
    surfaceContainerLow = Color(0xFF1B1B1F),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF2A2A2D),
    surfaceContainerHighest = Color(0xFF353438),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// ---- Per-accent roles ----

private val VioletLight = AccentRoles(
    primary = Color(0xFF6E4CE0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF), onPrimaryContainer = Color(0xFF22005C),
    inversePrimary = Color(0xFFCFBCFF),
    secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFFB0468E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8EC), onTertiaryContainer = Color(0xFF3B0027),
)
private val VioletDark = AccentRoles(
    primary = Color(0xFFCFBCFF), onPrimary = Color(0xFF38158A),
    primaryContainer = Color(0xFF5335C0), onPrimaryContainer = Color(0xFFE9DDFF),
    inversePrimary = Color(0xFF6E4CE0),
    secondary = Color(0xFFCBC2DB), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFFFAED6), onTertiary = Color(0xFF5E1147),
    tertiaryContainer = Color(0xFF7C2C60), onTertiaryContainer = Color(0xFFFFD8EC),
)

private val TealLight = AccentRoles(
    primary = Color(0xFF00687A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFACEDFF), onPrimaryContainer = Color(0xFF001F27),
    inversePrimary = Color(0xFF55D6F5),
    secondary = Color(0xFF4B6269), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7EF), onSecondaryContainer = Color(0xFF061F25),
    tertiary = Color(0xFF5B5C7E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE1E0FF), onTertiaryContainer = Color(0xFF171937),
)
private val TealDark = AccentRoles(
    primary = Color(0xFF55D6F5), onPrimary = Color(0xFF003641),
    primaryContainer = Color(0xFF004E5D), onPrimaryContainer = Color(0xFFACEDFF),
    inversePrimary = Color(0xFF00687A),
    secondary = Color(0xFFB1CBD3), onSecondary = Color(0xFF1C343A),
    secondaryContainer = Color(0xFF334A51), onSecondaryContainer = Color(0xFFCDE7EF),
    tertiary = Color(0xFFC3C3EB), onTertiary = Color(0xFF2C2E4D),
    tertiaryContainer = Color(0xFF434465), onTertiaryContainer = Color(0xFFE1E0FF),
)

private val OrangeLight = AccentRoles(
    primary = Color(0xFF9A4A00), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBE), onPrimaryContainer = Color(0xFF2E1500),
    inversePrimary = Color(0xFFFFB77C),
    secondary = Color(0xFF745943), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCBE), onSecondaryContainer = Color(0xFF2A1706),
    tertiary = Color(0xFF5B6236), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDFE7B0), onTertiaryContainer = Color(0xFF191E00),
)
private val OrangeDark = AccentRoles(
    primary = Color(0xFFFFB77C), onPrimary = Color(0xFF4D2700),
    primaryContainer = Color(0xFF6D3900), onPrimaryContainer = Color(0xFFFFDCBE),
    inversePrimary = Color(0xFF9A4A00),
    secondary = Color(0xFFE3C0A4), onSecondary = Color(0xFF422C19),
    secondaryContainer = Color(0xFF5B422D), onSecondaryContainer = Color(0xFFFFDCBE),
    tertiary = Color(0xFFC3CB97), onTertiary = Color(0xFF2D330D),
    tertiaryContainer = Color(0xFF434A21), onTertiaryContainer = Color(0xFFDFE7B0),
)

/** The fixed expressive [ColorScheme] for an [accent] in the given mode (the non-dynamic fallback). */
fun expressiveColorScheme(accent: CaAccent, dark: Boolean): ColorScheme {
    val roles = when (accent) {
        CaAccent.Violet -> if (dark) VioletDark else VioletLight
        CaAccent.Teal -> if (dark) TealDark else TealLight
        CaAccent.Orange -> if (dark) OrangeDark else OrangeLight
    }
    return (if (dark) NeutralDark else NeutralLight).withAccent(roles)
}
