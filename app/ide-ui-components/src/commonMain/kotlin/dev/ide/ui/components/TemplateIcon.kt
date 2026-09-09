package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.icons.IconTint
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.theme.TonalPair
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.resolveTint

/**
 * A project or template's **own** icon, on a tonal tile.
 *
 * Distinct from [TonalTile], which draws a Material Symbol. Templates and projects carry an `iconId` that
 * resolves through [TreeIcons] into branded art — the Android robot in Android green, a purple "K" for
 * Kotlin, a tan "J" for Java — and that identity is the point: a row of store items should be
 * recognisable by language at a glance, which a uniform symbol set cannot do.
 *
 * The tile stays tonal (so the shape rotation still reads) while the glyph keeps its brand colour, which
 * is the one sanctioned exception to "text on a container uses that container's `on*` role": these are
 * marks, not text.
 */
@Composable
fun TemplateIcon(
    iconId: String,
    pair: TonalPair,
    shape: Shape,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    contentDescription: String? = null,
) {
    Box(
        modifier.size(size).clip(shape).background(pair.container),
        contentAlignment = Alignment.Center,
    ) {
        TemplateGlyph(iconId, size = size * 0.52f, fallbackTint = pair.onContainer, contentDescription = contentDescription)
    }
}

/**
 * The bare branded glyph, with no tile behind it — for a hero tile that supplies its own background, or
 * anywhere the mark sits directly on a surface.
 *
 * A [TreeIcon.Badge] is a letter, so it is centred with a trimmed line box the way [dev.ide.ui.components.LetterBadge]
 * is: a bare `Text` sits low in its slot because the line reserves ascent and descent the glyph never uses.
 */
@Composable
fun TemplateGlyph(
    iconId: String,
    size: Dp,
    fallbackTint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    forceTint: Color? = null,
) {
    // No branded art registered for this id: fall through to the Material Symbols vocabulary rather than
    // drawing TreeIcons' generic page glyph, which says nothing and reads as a broken icon on a tonal tile.
    if (!TreeIcons.isRegistered(iconId)) {
        Symbol(
            glyph = CaSymbols.forIconId(iconId),
            contentDescription = contentDescription,
            size = size,
            tint = forceTint ?: fallbackTint,
            modifier = modifier,
        )
        return
    }
    when (val icon = TreeIcons.resolve(iconId)) {
        is TreeIcon.Glyph -> Icon(
            icon.image,
            contentDescription,
            modifier.size(size),
            tint = forceTint ?: tintOrFallback(icon.tint, fallbackTint),
        )
        is TreeIcon.Folder -> Icon(
            icon.closed,
            contentDescription,
            modifier.size(size),
            tint = forceTint ?: tintOrFallback(icon.tint, fallbackTint),
        )
        is TreeIcon.Badge -> Text(
            text = icon.text,
            color = forceTint ?: icon.color,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = androidx.compose.ui.unit.TextUnit(size.value * 0.86f, androidx.compose.ui.unit.TextUnitType.Sp),
                lineHeight = androidx.compose.ui.unit.TextUnit(size.value * 0.86f, androidx.compose.ui.unit.TextUnitType.Sp),
                fontWeight = FontWeight.Bold,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            softWrap = false,
            modifier = modifier,
        )
    }
}

/**
 * A theme-resolved tint, except for the unregistered fallback.
 *
 * `TreeIcons.resolve` answers a muted tertiary file glyph for an id it does not know. On a file tree that
 * is right; on a tonal store tile it disappears into the container, so an unresolved id takes the tile's
 * own `on*` colour instead.
 */
@Composable
private fun tintOrFallback(tint: IconTint, fallback: Color): Color =
    if (tint is IconTint.Tertiary) fallback else resolveTint(tint)
