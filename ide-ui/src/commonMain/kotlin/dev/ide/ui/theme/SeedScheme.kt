package dev.ide.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Generates a full Material 3 Expressive [ColorScheme] from a single seed color — the "custom accent"
 * path (Material You's choose-your-color, for desktop and non-dynamic Android). This is a self-contained
 * tonal generator (no material-color-utilities dependency): the seed's hue drives primary / secondary /
 * tertiary / neutral tonal palettes, and each Material role is read off a palette at the tone the M3 spec
 * assigns it (e.g. light primary = tone 40, its container = tone 90; dark primary = tone 80, container =
 * tone 30). Tones vary lightness in HSL — a perceptual approximation of MCU's HCT, close enough for a
 * coherent, legible custom theme.
 */

/** A tonal palette: fixed hue + chroma, [tone] (0..100 → light..? actually 0 = black, 100 = white). */
private class Tones(private val hue: Float, private val sat: Float) {
    fun tone(t: Int): Color = hslColor(hue, sat, t / 100f)
}

fun expressiveColorSchemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val (h, s0, _) = seed.toHsl()
    // A grayscale-ish seed yields a low-chroma theme rather than snapping to a hue.
    val chroma = if (s0 < 0.05f) 0.10f else s0
    val primary = Tones(h, chroma.coerceIn(0.32f, 0.95f))
    val secondary = Tones(h, (chroma * 0.35f).coerceIn(0.10f, 0.40f))
    val tertiary = Tones((h + 60f) % 360f, (chroma * 0.55f).coerceIn(0.28f, 0.75f))
    val neutral = Tones(h, 0.04f)
    val neutralV = Tones(h, 0.09f)
    val error = Tones(15f, 0.80f)

    return if (dark) darkColorScheme(
        primary = primary.tone(80), onPrimary = primary.tone(20),
        primaryContainer = primary.tone(30), onPrimaryContainer = primary.tone(90),
        inversePrimary = primary.tone(40),
        secondary = secondary.tone(80), onSecondary = secondary.tone(20),
        secondaryContainer = secondary.tone(30), onSecondaryContainer = secondary.tone(90),
        tertiary = tertiary.tone(80), onTertiary = tertiary.tone(20),
        tertiaryContainer = tertiary.tone(30), onTertiaryContainer = tertiary.tone(90),
        background = neutral.tone(6), onBackground = neutral.tone(90),
        surface = neutral.tone(6), onSurface = neutral.tone(90),
        surfaceVariant = neutralV.tone(30), onSurfaceVariant = neutralV.tone(80),
        surfaceTint = primary.tone(80),
        inverseSurface = neutral.tone(90), inverseOnSurface = neutral.tone(20),
        outline = neutralV.tone(60), outlineVariant = neutralV.tone(30),
        scrim = Color.Black,
        surfaceBright = neutral.tone(24), surfaceDim = neutral.tone(6),
        surfaceContainerLowest = neutral.tone(4), surfaceContainerLow = neutral.tone(10),
        surfaceContainer = neutral.tone(12), surfaceContainerHigh = neutral.tone(17),
        surfaceContainerHighest = neutral.tone(22),
        error = error.tone(80), onError = error.tone(20),
        errorContainer = error.tone(30), onErrorContainer = error.tone(90),
    ) else lightColorScheme(
        primary = primary.tone(40), onPrimary = primary.tone(100),
        primaryContainer = primary.tone(90), onPrimaryContainer = primary.tone(10),
        inversePrimary = primary.tone(80),
        secondary = secondary.tone(40), onSecondary = secondary.tone(100),
        secondaryContainer = secondary.tone(90), onSecondaryContainer = secondary.tone(10),
        tertiary = tertiary.tone(40), onTertiary = tertiary.tone(100),
        tertiaryContainer = tertiary.tone(90), onTertiaryContainer = tertiary.tone(10),
        background = neutral.tone(98), onBackground = neutral.tone(10),
        surface = neutral.tone(98), onSurface = neutral.tone(10),
        surfaceVariant = neutralV.tone(90), onSurfaceVariant = neutralV.tone(30),
        surfaceTint = primary.tone(40),
        inverseSurface = neutral.tone(20), inverseOnSurface = neutral.tone(95),
        outline = neutralV.tone(50), outlineVariant = neutralV.tone(80),
        scrim = Color.Black,
        surfaceBright = neutral.tone(98), surfaceDim = neutral.tone(87),
        surfaceContainerLowest = neutral.tone(100), surfaceContainerLow = neutral.tone(96),
        surfaceContainer = neutral.tone(94), surfaceContainerHigh = neutral.tone(92),
        surfaceContainerHighest = neutral.tone(90),
        error = error.tone(40), onError = error.tone(100),
        errorContainer = error.tone(90), onErrorContainer = error.tone(10),
    )
}

// ---- HSL <-> sRGB helpers (h in degrees 0..360, s/l in 0..1) ----

private data class Hsl(val h: Float, val s: Float, val l: Float)

private fun Color.toHsl(): Hsl {
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d < 1e-5f) return Hsl(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return Hsl((h + 360f) % 360f, s, l)
}

private fun hslColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f))
}
