package dev.ide.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.ide.ui.theme.colors.ContributedAppTheme

/**
 * The Material [ColorScheme] a plugin's [ContributedAppTheme] renders as: the scheme generated from its seed
 * (or the default Lime scheme without one), with every role the theme names laid over it. A theme exported
 * from Material Theme Builder names them all, so nothing of the base shows through; a hand-written one that
 * names a few gets a coherent scheme around them.
 */
fun appThemeColorScheme(theme: ContributedAppTheme, dark: Boolean): ColorScheme {
    val base = theme.seed?.let { expressiveColorSchemeFromSeed(it, dark) } ?: expressiveColorScheme(CaAccent.Lime, dark)
    return base.withRoles(theme.roles(dark))
}

/** [this] with the roles in [roles] replaced, by Material role name. A name this Material has no role for is ignored. */
fun ColorScheme.withRoles(roles: Map<String, Color>): ColorScheme {
    if (roles.isEmpty()) return this
    fun r(name: String, current: Color): Color = roles[name] ?: current
    return copy(
        primary = r("primary", primary),
        onPrimary = r("onPrimary", onPrimary),
        primaryContainer = r("primaryContainer", primaryContainer),
        onPrimaryContainer = r("onPrimaryContainer", onPrimaryContainer),
        inversePrimary = r("inversePrimary", inversePrimary),
        secondary = r("secondary", secondary),
        onSecondary = r("onSecondary", onSecondary),
        secondaryContainer = r("secondaryContainer", secondaryContainer),
        onSecondaryContainer = r("onSecondaryContainer", onSecondaryContainer),
        tertiary = r("tertiary", tertiary),
        onTertiary = r("onTertiary", onTertiary),
        tertiaryContainer = r("tertiaryContainer", tertiaryContainer),
        onTertiaryContainer = r("onTertiaryContainer", onTertiaryContainer),
        background = r("background", background),
        onBackground = r("onBackground", onBackground),
        surface = r("surface", surface),
        onSurface = r("onSurface", onSurface),
        surfaceVariant = r("surfaceVariant", surfaceVariant),
        onSurfaceVariant = r("onSurfaceVariant", onSurfaceVariant),
        surfaceTint = r("surfaceTint", surfaceTint),
        inverseSurface = r("inverseSurface", inverseSurface),
        inverseOnSurface = r("inverseOnSurface", inverseOnSurface),
        error = r("error", error),
        onError = r("onError", onError),
        errorContainer = r("errorContainer", errorContainer),
        onErrorContainer = r("onErrorContainer", onErrorContainer),
        outline = r("outline", outline),
        outlineVariant = r("outlineVariant", outlineVariant),
        scrim = r("scrim", scrim),
        surfaceBright = r("surfaceBright", surfaceBright),
        surfaceDim = r("surfaceDim", surfaceDim),
        surfaceContainer = r("surfaceContainer", surfaceContainer),
        surfaceContainerHigh = r("surfaceContainerHigh", surfaceContainerHigh),
        surfaceContainerHighest = r("surfaceContainerHighest", surfaceContainerHighest),
        surfaceContainerLow = r("surfaceContainerLow", surfaceContainerLow),
        surfaceContainerLowest = r("surfaceContainerLowest", surfaceContainerLowest),
        primaryFixed = r("primaryFixed", primaryFixed),
        primaryFixedDim = r("primaryFixedDim", primaryFixedDim),
        onPrimaryFixed = r("onPrimaryFixed", onPrimaryFixed),
        onPrimaryFixedVariant = r("onPrimaryFixedVariant", onPrimaryFixedVariant),
        secondaryFixed = r("secondaryFixed", secondaryFixed),
        secondaryFixedDim = r("secondaryFixedDim", secondaryFixedDim),
        onSecondaryFixed = r("onSecondaryFixed", onSecondaryFixed),
        onSecondaryFixedVariant = r("onSecondaryFixedVariant", onSecondaryFixedVariant),
        tertiaryFixed = r("tertiaryFixed", tertiaryFixed),
        tertiaryFixedDim = r("tertiaryFixedDim", tertiaryFixedDim),
        onTertiaryFixed = r("onTertiaryFixed", onTertiaryFixed),
        onTertiaryFixedVariant = r("onTertiaryFixedVariant", onTertiaryFixedVariant),
    )
}
