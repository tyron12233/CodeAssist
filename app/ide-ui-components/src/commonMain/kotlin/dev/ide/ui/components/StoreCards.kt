package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.store_installs
import dev.ide.ui.generated.resources.store_trending
import dev.ide.ui.generated.resources.store_trending_search
import dev.ide.ui.generated.resources.store_projects
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.CaShapes
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.TonalPair
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
 * Explore's featured card: the project's own icon and name on a tonal ground, with two of its real
 * screenshots fanned in phone frames off the bottom-right corner.
 *
 * The phones bleed past the card's bottom and right edges, and that overhang is the card's silhouette:
 * it is why the card clips its content. A project that shipped no screenshots gets the abstract code
 * panel in the same corner instead, so the card keeps its shape either way.
 *
 * [shots] are drawn front first. Only the first two are used; a third phone would cover the text column.
 */
@Composable
fun FeaturedHeroCard(
    title: String,
    subtitle: String,
    pair: TonalPair,
    motif: List<MotifLine>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A small label above the icon. Null hides it, which is right when it would say the same thing on every card. */
    badge: String? = null,
    verified: Boolean = false,
    rating: Float = -1f,
    installs: Int = -1,
    /** The glyph drawn when there is no [icon]. */
    iconId: String = "file",
    /** The project's own app icon, filling the tile. Null draws the [iconId] glyph. */
    icon: (@Composable (Modifier) -> Unit)? = null,
    /** Screenshots, front first. Empty draws the code motif. */
    shots: List<@Composable (Modifier) -> Unit> = emptyList(),
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = CaShapes.Hero,
        color = pair.container,
        contentColor = pair.onContainer,
        shadowElevation = 1.dp,
        interactionSource = interaction,
        modifier = modifier.width(324.dp).height(236.dp).pressScale(interaction, pressed = 0.99f),
    ) {
        Box(Modifier.clipToBounds()) {
            if (shots.isNotEmpty()) {
                // The back phone first, so the front one is drawn over it.
                shots.getOrNull(1)?.let { back ->
                    PhoneFrame(
                        back,
                        Modifier.align(Alignment.BottomEnd).offset(x = 30.dp, y = 30.dp).rotate(9f),
                    )
                }
                PhoneFrame(
                    shots.first(),
                    Modifier.align(Alignment.BottomEnd).offset(x = (-22).dp, y = 52.dp).rotate(-5f),
                )
            } else {
                CodeMotif(
                    lines = motif,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 18.dp).width(150.dp),
                    rotation = -6f,
                )
            }
            Column(Modifier.fillMaxHeight().width(184.dp).padding(start = 20.dp, top = 20.dp, bottom = 18.dp)) {
                if (badge != null) {
                    Eyebrow(badge, color = pair.onContainer.copy(alpha = 0.75f), modifier = Modifier.padding(bottom = 8.dp))
                }
                if (icon != null) {
                    icon(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)))
                } else {
                    TemplateIcon(
                        iconId,
                        TonalPair(pair.onContainer.copy(alpha = 0.12f), pair.onContainer),
                        RoundedCornerShape(14.dp),
                        size = 52.dp,
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = pair.onContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
                PublisherLine(subtitle, verified, pair.onContainer.copy(alpha = 0.72f), Modifier.padding(top = 2.dp))
                Spacer(Modifier.weight(1f))
                StatLine(rating, installs, pair.onContainer)
            }
        }
    }
}

/**
 * Editor's Choice: a portrait card that is mostly the project's first screenshot, with its icon, name
 * and one-line pitch set over a dark scrim at the bottom.
 *
 * Deliberately a different object from [FeaturedHeroCard] rather than the same card again: the hero
 * says "here is a project", this says "here is what it looks like". The text over the scrim is always
 * light, whatever the theme, because it sits on a darkened photograph and not on a theme surface.
 *
 * Without a screenshot it falls back to a tonal ground with the icon large, and the text takes the
 * ground's `on*` colour instead.
 */
@Composable
fun EditorialCard(
    title: String,
    author: String,
    blurb: String,
    pair: TonalPair,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    verified: Boolean = false,
    rating: Float = -1f,
    installs: Int = -1,
    iconId: String = "file",
    icon: (@Composable (Modifier) -> Unit)? = null,
    /** The screenshot that fills the card. Null draws the tonal fallback. */
    shot: (@Composable (Modifier) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val ink = if (shot != null) Color.White else pair.onContainer
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = if (shot != null) CodeMotifColors.Chrome else pair.container,
        contentColor = ink,
        shadowElevation = 1.dp,
        interactionSource = interaction,
        modifier = modifier.width(256.dp).height(352.dp).pressScale(interaction, pressed = 0.99f),
    ) {
        Box {
            if (shot != null) {
                shot(Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            // Opaque enough under the text that a light, busy screenshot cannot show
                            // through the title: the text block starts a little above the middle.
                            0f to Color.Transparent,
                            0.30f to Color.Transparent,
                            0.55f to Color.Black.copy(alpha = 0.72f),
                            0.72f to Color.Black.copy(alpha = 0.90f),
                            1f to Color.Black.copy(alpha = 0.96f),
                        ),
                    ),
                )
            } else {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    if (icon != null) {
                        icon(Modifier.size(96.dp).clip(RoundedCornerShape(26.dp)))
                    } else {
                        TemplateIcon(
                            iconId,
                            TonalPair(pair.onContainer.copy(alpha = 0.12f), pair.onContainer),
                            RoundedCornerShape(26.dp),
                            size = 96.dp,
                        )
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (shot != null) {
                        if (icon != null) {
                            icon(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                        } else {
                            TemplateIcon(iconId, pair, RoundedCornerShape(12.dp), size = 44.dp)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        PublisherLine(author, verified, ink.copy(alpha = 0.75f))
                    }
                }
                if (blurb.isNotBlank()) {
                    Text(
                        blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = ink.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                StatLine(rating, installs, ink, Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** A screenshot in a thin dark phone bezel, portrait, the shape a phone screenshot was taken in. */
@Composable
private fun PhoneFrame(shot: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    val outer = RoundedCornerShape(20.dp)
    Box(
        modifier.width(104.dp).height(218.dp)
            .shadow(10.dp, outer)
            .clip(outer)
            .background(CodeMotifColors.ChromeBar)
            .padding(3.dp)
            .clearAndSetSemantics {},
    ) {
        shot(Modifier.fillMaxSize().clip(RoundedCornerShape(17.dp)))
    }
}

/** "Aio ✓": the publisher, with the verified mark where they carry one. */
@Composable
private fun PublisherLine(name: String, verified: Boolean, color: Color, modifier: Modifier = Modifier) {
    if (name.isBlank()) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (verified) Symbol(CaSymbols.verified, contentDescription = null, size = 15.dp, filled = true, tint = color)
    }
}

/** "★ 4.7 · 690 installs". Each half hides on its own when there is nothing true to say. */
@Composable
private fun StatLine(rating: Float, installs: Int, color: Color, modifier: Modifier = Modifier) {
    if (rating < 0f && installs < 0) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rating >= 0f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Symbol(CaSymbols.star, contentDescription = null, size = 16.dp, filled = true, tint = color)
                Text(
                    formatRating(rating),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = color,
                )
            }
        }
        if (installs >= 0) SupportingOnContainer(installLabel(installs), color)
    }
}

/**
 * The pitch a card shows for a project, cleaned of what publishers paste into it.
 *
 * Descriptions arrive as READMEs: `# Title`, `## Features`, `**bold**`, a first line that repeats the
 * project's name, "App Name: X / Version: 1.0" headers, a lone "1." left over from a numbered list. On a
 * two-line card all of that is noise standing where the actual sentence should be. This keeps the prose
 * and drops the scaffolding; when nothing survives, it falls back to [fallback] rather than to nothing.
 */
fun storeBlurb(text: String?, title: String, fallback: String = ""): String {
    val header = Regex("""^(app\s*name|name|version|ver)\s*[:：].*$""", RegexOption.IGNORE_CASE)
    val label = Regex("""^[\[【].*[\]】]$""")
    val numbering = Regex("""^\d+[.)]?$""")
    val cleaned = (text ?: "").lines().asSequence()
        .map { line ->
            line.trim()
                .replace(Regex("""^#+\s*"""), "")
                .replace(Regex("""^[-*•]\s+"""), "")
                .replace("**", "").replace("__", "").replace("`", "")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .filterNot { it.equals(title.trim(), ignoreCase = true) }
        .filterNot { header.matches(it) || label.matches(it) || numbering.matches(it) }
        .joinToString(" ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return cleaned.ifEmpty { fallback.trim() }
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
 * What people are looking for, as a row of search shortcuts under the search field.
 *
 * Replaced a marquee of tonal chips that scrolled forever and did nothing when tapped: it moved while
 * the reader was trying to read the page under it, it was hidden from screen readers because a moving
 * band cannot be narrated, and each chip carried the same trending arrow, so the arrow said nothing.
 * Now it holds still, scrolls under the finger, says "Trending" once, and each term opens the search
 * with that term already typed.
 */
@Composable
fun TrendingSearches(
    terms: List<String>,
    onTerm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (terms.isEmpty()) return
    val c = MaterialTheme.colorScheme
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item("label") {
            Row(
                Modifier.padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol(CaSymbols.trendingUp, contentDescription = null, size = 18.dp, tint = c.primary)
                Text(
                    stringResource(Res.string.store_trending),
                    style = MaterialTheme.typography.labelLarge,
                    color = c.primary,
                )
            }
        }
        items(terms, key = { it }) { term ->
            val description = stringResource(Res.string.store_trending_search, term)
            Surface(
                onClick = { onTerm(term) },
                shape = CircleShape,
                color = c.surfaceContainerHigh,
                contentColor = c.onSurface,
                modifier = Modifier.height(36.dp).semantics { contentDescription = description },
            ) {
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(term, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/** `48K`, `1.2K`, `940`. Abbreviated the way the design writes install counts. */
@Composable
fun installLabel(installs: Int): String {
    val formatted = when {
        installs >= 1_000_000 -> "${(installs / 100_000) / 10.0}M"
        installs >= 10_000 -> "${installs / 1_000}K"
        installs >= 1_000 -> "${(installs / 100) / 10.0}K"
        else -> installs.toString()
    }
    return pluralStringResource(Res.plurals.store_installs, installs, formatted)
}

@Composable
private fun countLabel(count: Int): String = pluralStringResource(Res.plurals.store_projects, count, count)

/** One decimal place, always — "4.8", never "4.80" or "5". Shared with the chart rows. */
fun formatRating(rating: Float): String {
    val tenths = (rating.coerceIn(0f, 5f) * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

