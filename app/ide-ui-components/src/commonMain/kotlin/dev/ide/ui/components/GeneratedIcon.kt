package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol

/**
 * The icon a published project gets when it shipped none: a gradient chosen from its id and a monogram
 * from its name, with a puzzle-piece mark for a plugin.
 *
 * Plugins are why this exists. A plugin is not an Android app, so it has no launcher icon to read, and
 * every one of them used to draw the same generic glyph tile: a shelf of plugins was a row of identical
 * squares. The gradient tells two plugins apart, the letters say which one it is, and the mark says it
 * is a plugin before the title is read.
 *
 * The colours are fixed rather than taken from the theme, because an app icon is the project's own and
 * should look the same on every device and in both themes. Draws edge to edge: the caller clips it to
 * whatever tile shape it uses, exactly as it would a real icon.
 */
@Composable
fun GeneratedIcon(
    seed: String,
    title: String,
    plugin: Boolean,
    modifier: Modifier = Modifier,
) {
    val (start, end) = generatedIconColors(seed)
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .background(Brush.linearGradient(listOf(start, end), start = Offset.Zero, end = Offset.Infinite))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)
        if (plugin) {
            Symbol(
                CaSymbols.extension,
                contentDescription = null,
                // Whole, not cropped off the corner: cut down to a sliver it read as a smudge, and the
                // shape is the whole point of the mark.
                size = side * 0.56f,
                filled = true,
                tint = Color.White.copy(alpha = 0.26f),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = side * 0.07f, y = side * 0.07f),
            )
        }
        val letters = monogram(title)
        // Sized from the tile, not from the font scale: this is artwork, and an icon whose letters grew
        // with the reader's text size would burst out of its own tile.
        val fontSize = with(LocalDensity.current) { (side * if (letters.length > 1) 0.40f else 0.50f).toSp() }
        Text(
            letters,
            color = Color.White,
            style = TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).em,
                lineHeight = fontSize,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            maxLines = 1,
        )
    }
}

/**
 * Up to two letters for [title]: the initials of its first two words, ignoring the word "plugin" (which
 * half the plugins carry, and which the mark already says), or of a single camelCase word's two halves.
 * A one-word name gets its first letter.
 */
fun monogram(title: String): String {
    val words = title.split(Regex("""[\s_\-.:/]+"""))
        .filter { it.isNotBlank() && !it.equals("plugin", ignoreCase = true) && !it.equals("plugins", ignoreCase = true) }
        .ifEmpty { listOf(title.trim()) }
    val parts = if (words.size >= 2) words else camelHalves(words.first())
    val letters = parts.mapNotNull { part -> part.firstOrNull { it.isLetterOrDigit() } }.take(2)
    return letters.joinToString("").uppercase().ifEmpty { "?" }
}

/** "ColorPicker" -> ["Color", "Picker"]; a word with no lower-to-upper step stays whole. */
private fun camelHalves(word: String): List<String> {
    val cut = (1 until word.length).firstOrNull { word[it].isUpperCase() && word[it - 1].isLowerCase() }
        ?: return listOf(word)
    return listOf(word.substring(0, cut), word.substring(cut))
}

/** Whether an item is a plugin: the store files plugins under `build`, and some publishers only tag them. */
fun isPluginListing(category: String?, tags: List<String>): Boolean =
    category.equals("build", ignoreCase = true) || category.equals("plugins", ignoreCase = true) ||
        tags.any { it.equals("plugin", ignoreCase = true) }

/**
 * The gradient for [seed]. Chosen with Java's `String.hashCode`, which the admin page reimplements, so a
 * plugin has the same colours in the moderator's preview as on a phone.
 */
fun generatedIconColors(seed: String): Pair<Color, Color> =
    GENERATED_ICON_PALETTE[seed.hashCode().mod(GENERATED_ICON_PALETTE.size)]

/** Every pair is dark enough at its light end for white letters. Mirrored in supabase/admin/index.html. */
private val GENERATED_ICON_PALETTE = listOf(
    Color(0xFF4F46E5) to Color(0xFF8B5CF6), // indigo to violet
    Color(0xFF0F766E) to Color(0xFF14B8A6), // teal
    Color(0xFFC2410C) to Color(0xFFF97316), // orange
    Color(0xFFA21CAF) to Color(0xFFEC4899), // magenta to pink
    Color(0xFF1D4ED8) to Color(0xFF0EA5E9), // blue to sky
    Color(0xFF3F6212) to Color(0xFF65A30D), // olive
    Color(0xFFBE123C) to Color(0xFFF43F5E), // rose
    Color(0xFF6D28D9) to Color(0xFFC026D3), // purple to fuchsia
    Color(0xFF0369A1) to Color(0xFF06B6D4), // ocean to cyan
    Color(0xFF9A3412) to Color(0xFFD97706), // rust to amber
    Color(0xFF15803D) to Color(0xFF22C55E), // green
    Color(0xFF334155) to Color(0xFF64748B), // slate
)
