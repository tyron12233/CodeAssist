package dev.ide.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.CaShapes
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.TonalPair
import kotlin.math.roundToInt

/**
 * The Explore tab's card vocabulary.
 *
 * The recurring device here is a **tonal card with a dark code panel laid over it** — the panel is the one
 * place in the design that ignores the theme entirely, because a code sample reads as code only against a
 * dark editor chrome. Its palette is fixed (see [CodeMotifColors]) and does not flip in light mode.
 */

/** The fixed editor palette the code motifs are drawn in. Independent of the app theme, by design. */
object CodeMotifColors {
    val Chrome = Color(0xFF151321)
    val ChromeBar = Color(0xFF0E0D16)
    val Gutter = Color(0xFF111020)
    val TreeBar = Color(0xFF2E2C42)
    val Keyword = Color(0xFFC792EA)
    val Identifier = Color(0xFF82AAFF)
    val StringLit = Color(0xFFC3E88D)
    val Plain = Color(0xFFA6ACCD)
    val Comment = Color(0xFF5C6370)
    val FileName = Color(0xFF7C7B95)
    val Dot = Color(0xFF3B3A4A)
}

/** One line of the abstract code motif: how wide it runs, what colour it is, and how far it is indented. */
@Immutable
data class MotifLine(val widthFraction: Float, val color: Color, val indent: Dp)

/**
 * A deterministic abstract code sample.
 *
 * Deliberately abstract rather than real source: a store card has no source to show until an item is
 * downloaded, and inventing plausible-looking code would be worse than an obvious stand-in. Derived from
 * [seed] so a given item always draws the same shape rather than reshuffling on every recomposition.
 */
fun motifFor(seed: String, lines: Int = 5): List<MotifLine> {
    val palette = listOf(
        CodeMotifColors.Keyword, CodeMotifColors.Identifier, CodeMotifColors.StringLit,
        CodeMotifColors.Plain, CodeMotifColors.Comment,
    )
    var h = seed.hashCode().let { if (it == Int.MIN_VALUE) 1 else it }
    return List(lines) {
        h = h * 1_103_515_245 + 12_345
        val a = (h ushr 8) and 0xFF
        val b = (h ushr 16) and 0xFF
        MotifLine(
            widthFraction = 0.30f + (a % 50) / 100f,
            color = palette[b % palette.size],
            indent = listOf(0, 12, 12, 24)[a % 4].dp,
        )
    }
}

/** The rotated dark code panel that overhangs a hero card's bottom edge. */
@Composable
fun CodeMotif(
    lines: List<MotifLine>,
    modifier: Modifier = Modifier,
    rotation: Float = -2.5f,
) = Column(
    modifier
        .rotate(rotation)
        .clip(RoundedCornerShape(16.dp))
        .background(CodeMotifColors.Chrome)
        .padding(horizontal = 14.dp, vertical = 12.dp)
        .clearAndSetSemantics {},
    verticalArrangement = Arrangement.spacedBy(7.dp),
) {
    lines.forEach { l ->
        Box(
            Modifier.padding(start = l.indent)
                .fillMaxWidth(l.widthFraction)
                .height(6.dp)
                .clip(CircleShape)
                .background(l.color),
        )
    }
}

/**
 * Explore's 296 dp featured card.
 *
 * The code panel is inset from the left and pulled **past the card's bottom edge**, then the footer sits
 * below it. That overhang is the card's whole silhouette: it is why the card clips its content and why the
 * footer needs the large top gap it has.
 */
@Composable
fun FeaturedHeroCard(
    title: String,
    badge: String,
    subtitle: String,
    pair: TonalPair,
    motif: List<MotifLine>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rating: Float = -1f,
    installs: Int = -1,
    /** A real screenshot to show in place of the abstract motif. Null ⇒ draw the motif. */
    preview: (@Composable (Modifier) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = CaShapes.Hero,
        color = pair.container,
        contentColor = pair.onContainer,
        shadowElevation = 1.dp,
        interactionSource = interaction,
        modifier = modifier.width(296.dp).pressScale(interaction, pressed = 0.99f),
    ) {
        Column(Modifier.clipToBounds().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
            Surface(shape = RoundedCornerShape(9.dp), color = pair.onContainer, contentColor = pair.container) {
                Text(
                    badge.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            Text(
                title,
                // titleLarge, not headlineSmall: this is a card in a row of cards, not the page's title,
                // and at 24 sp it wrapped to two lines for most real project names.
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                color = pair.onContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
            SupportingOnContainer(subtitle, pair.onContainer, Modifier.padding(top = 6.dp), maxLines = 1)
            // Inset from the left, bleeding 6 dp past the card's right padding, with only a 10 dp gap to
            // the footer below it. The design gets that tight gap from a negative bottom margin on the
            // panel; the same result here is just a small top padding on the footer.
            //
            // A real screenshot wins over the motif: the motif exists because most catalog items ship no
            // artwork, not because abstract bars are preferable to a picture of the thing.
            val panel = Modifier.padding(start = 40.dp, top = 16.dp).offset(x = 6.dp).fillMaxWidth()
            if (preview != null) {
                preview(panel.height(104.dp).clip(RoundedCornerShape(16.dp)).rotate(-2.5f))
            } else {
                CodeMotif(lines = motif, modifier = panel)
            }
            Row(
                Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (rating >= 0f) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Symbol(CaSymbols.star, contentDescription = null, size = 16.dp, filled = true, tint = pair.onContainer)
                        Text(
                            formatRating(rating),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = pair.onContainer,
                        )
                    }
                }
                if (installs >= 0) {
                    SupportingOnContainer(installLabel(installs), pair.onContainer)
                }
            }
        }
    }
}

/** A 104 dp "browse by kind" tile: a glyph, the name, a count, and an oversized clipped watermark. */
@Composable
fun CategoryTile(
    name: String,
    count: Int,
    glyph: Char,
    pair: TonalPair,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = shape,
        color = pair.container,
        contentColor = pair.onContainer,
        interactionSource = interaction,
        modifier = modifier.height(104.dp).pressScale(interaction),
    ) {
        Box(Modifier.clipToBounds()) {
            WatermarkGlyph(
                glyph = glyph,
                onContainer = pair.onContainer,
                size = 82.dp,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 18.dp, y = 22.dp),
            )
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Symbol(glyph, contentDescription = null, size = 24.dp, tint = pair.onContainer)
                Spacer(Modifier.height(8.dp))
                Column {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 19.sp),
                        color = pair.onContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SupportingOnContainer(countLabel(count), pair.onContainer)
                }
            }
        }
    }
}

/**
 * One project, as a row: icon, title, publisher, a single stat line, and the action.
 *
 * **Three text elements, not eight.** This is the store's one repeated item surface, and everything it
 * does not show is one tap away on the detail page. The version it replaced carried a badge, a published
 * date, a two-line title, the author AND language, a blurb, three tag chips and two stat figures, which
 * made one project 480 dp tall; six projects now fit where one did.
 *
 * [meta] is the whole stat line, composed by the caller, because what is worth showing differs by item:
 * `Kotlin · 4.5 ★ · 6.8 MB` for a published project, `Kotlin · offline` for a bundled scaffold. The
 * language leads it rather than sitting in its own chip: it is the identity a code project has and an app
 * does not, and there is no room for a row of chips here.
 */
@Composable
fun StoreListRow(
    title: String,
    subtitle: String,
    iconId: String,
    pair: TonalPair,
    tileShape: Shape,
    actionLabel: String,
    actionFilled: Boolean,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    /** The stat line under the publisher. Blank hides it, which is what a row with nothing to report does. */
    meta: String = "",
    /** The project's own app icon, filling the tile. Null draws the [iconId] glyph instead. */
    icon: (@Composable (Modifier) -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            icon(Modifier.size(56.dp).clip(tileShape))
        } else {
            TemplateIcon(iconId, pair, tileShape, size = 56.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Surface(
            onClick = onAction,
            shape = CircleShape,
            color = if (actionFilled) c.primary else Color.Transparent,
            contentColor = if (actionFilled) c.onPrimary else c.primary,
            border = if (actionFilled) null else androidx.compose.foundation.BorderStroke(1.dp, c.outline),
            modifier = Modifier.height(38.dp),
        ) {
            Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The trending marquee: a row of tonal chips that scrolls forever.
 *
 * Renders the list **twice** and translates by exactly half the total width, so the moment the first copy
 * scrolls out the second is already in position and the loop has no visible seam. Purely decorative, so
 * the whole strip is cleared from the semantics tree — a screen reader should not narrate a moving band of
 * marketing chips.
 */
@Composable
fun TrendingTicker(
    labels: List<String>,
    pairAt: @Composable (Int) -> TonalPair,
    modifier: Modifier = Modifier,
    durationMillis: Int = 26_000,
) {
    if (labels.isEmpty()) return
    var totalWidth by remember { mutableStateOf(0) }
    val transition = rememberInfiniteTransition(label = "ticker")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tickerOffset",
    )
    Box(modifier.fillMaxWidth().clipToBounds().clearAndSetSemantics {}) {
        Row(
            Modifier
                .onSizeChanged { totalWidth = it.width }
                .offset { IntOffset(-(totalWidth / 2f * progress).roundToInt(), 0) },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Twice through, so the second copy covers the gap the first leaves as it exits.
            repeat(2) { copy ->
                labels.forEachIndexed { i, label ->
                    val pair = pairAt(i)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = pair.container,
                        contentColor = pair.onContainer,
                        modifier = Modifier.height(30.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Symbol(CaSymbols.trendingUp, contentDescription = null, size = 14.dp)
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** `48K`, `1.2K`, `940`. Abbreviated the way the design writes install counts. */
fun installLabel(installs: Int): String = when {
    installs >= 1_000_000 -> "${(installs / 100_000) / 10.0}M installs"
    installs >= 10_000 -> "${installs / 1_000}K installs"
    installs >= 1_000 -> "${(installs / 100) / 10.0}K installs"
    else -> "$installs installs"
}

private fun countLabel(count: Int): String = if (count == 1) "1 project" else "$count projects"

/** One decimal place, always — "4.8", never "4.80" or "5". Shared with the chart rows. */
fun formatRating(rating: Float): String {
    val tenths = (rating.coerceIn(0f, 5f) * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

