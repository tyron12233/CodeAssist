package dev.ide.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.material_symbols_rounded
import dev.ide.ui.generated.resources.material_symbols_rounded_filled
import dev.ide.ui.icons.CaSymbols
import org.jetbrains.compose.resources.Font

/**
 * The two Material Symbols faces. Filled is a separate font rather than a variation setting because the
 * bundled files are static instances of the variable font's FILL axis — see [CaSymbols] for why, and for
 * the recipe that regenerates them.
 */
@Immutable
class SymbolFonts(val outline: FontFamily, val filled: FontFamily) {
    fun family(filled: Boolean): FontFamily = if (filled) this.filled else outline
}

@Composable
fun rememberMaterialSymbols(): SymbolFonts {
    val outline = Font(Res.font.material_symbols_rounded, FontWeight.Normal, FontStyle.Normal)
    val filled = Font(Res.font.material_symbols_rounded_filled, FontWeight.Normal, FontStyle.Normal)
    return remember(outline, filled) { SymbolFonts(FontFamily(outline), FontFamily(filled)) }
}

/**
 * Draw one Material Symbols glyph at [size].
 *
 * The glyph is text, so it needs the two corrections text normally applies and an icon must not:
 *
 * - `Dp.toSp()` converts through the current density, which folds out the user's font scale. A 24.dp
 *   icon stays 24.dp at `fontScale = 2.0` while the labels beside it grow, which is what the design
 *   intends: scaling icons with type makes a nav bar collapse.
 * - The line box is trimmed and centred ([LineHeightStyle]), because a text line reserves ascent and
 *   descent that a square glyph does not use, and without the trim every icon sits low in its slot.
 *
 * [contentDescription] is required to be explicit: pass a label for a meaningful icon, or `null` for a
 * decorative one (watermarks, the trending ticker), which clears the node from the semantics tree.
 */
@Composable
fun Symbol(
    glyph: Char,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    filled: Boolean = false,
    symbols: SymbolFonts = rememberMaterialSymbols(),
) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    val semantics = if (contentDescription == null) {
        Modifier.clearAndSetSemantics {}
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Box(modifier.size(size).then(semantics), contentAlignment = Alignment.Center) {
        Text(
            text = glyph.toString(),
            color = tint,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontFamily = symbols.family(filled),
            softWrap = false,
            textAlign = TextAlign.Center,
            style = MaterialSymbolLineStyle,
        )
    }
}

/**
 * As [Symbol], but for a glyph name that arrived as data (a store row's `icon` column, a category's
 * glyph). Falls back to [fallback] when the subset does not carry that name, so an unknown icon renders
 * as something rather than as a missing-glyph box.
 */
@Composable
fun Symbol(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    filled: Boolean = false,
    fallback: Char = CaSymbols.folder,
    symbols: SymbolFonts = rememberMaterialSymbols(),
) = Symbol(
    glyph = CaSymbols.byName(name) ?: fallback,
    contentDescription = contentDescription,
    modifier = modifier,
    size = size,
    tint = tint,
    filled = filled,
    symbols = symbols,
)

private val MaterialSymbolLineStyle = androidx.compose.ui.text.TextStyle(
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)
