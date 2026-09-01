package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiGhostShelf
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.cardShape
import dev.ide.ui.theme.tileShape
import dev.ide.ui.theme.tonalPair

/**
 * The sparse and empty Explore states.
 *
 * Two rules from the handoff shape everything here:
 *
 *  1. **Never fake abundance.** Four projects spread across five shelves looks abandoned; the same four
 *     in one generous list looks curated. So the sparse state has ONE catalogue list of full-bleed cards,
 *     not a set of thin carousels.
 *  2. **Content before pitch.** The user opened Explore to get something, not to publish. The catalogue
 *     comes first and the publishing argument follows it. Only the empty state may lead with the pitch,
 *     because there is nothing else to lead with.
 */

/** The header count badge: the honest disclosure that stops a short page reading as a bug. */
@Composable
fun StoreCountBadge(count: Int, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = c.surfaceContainerHigh,
        contentColor = c.onSurfaceVariant,
        modifier = modifier.height(28.dp)
            .semantics { contentDescription = "$count projects published" },
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                if (count == 1) "1 PROJECT" else "$count PROJECTS",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * A full-bleed tonal catalogue card — the sparse state's unit.
 *
 * Deliberately not a list row. With a handful of projects, a generous card per project reads as
 * curation; a compact row reads as a short list.
 */
@Composable
fun SparseProjectCard(
    item: UiStoreItem,
    index: Int,
    /** "Published 3 days ago", computed by the caller which knows the clock. */
    postedLabel: String?,
    /** Whether this was published inside the badge's 14-day window. The caller owns the clock. */
    isRecent: Boolean,
    actionLabel: String,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pair = tonalPair(index)
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        shape = cardShape(index),
        color = pair.container,
        contentColor = pair.onContainer,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp).pressScale(interaction, pressed = 0.995f),
    ) {
        Box(Modifier.clipToBounds()) {
            WatermarkGlyph(
                glyph = CaSymbols.forIconId(item.iconId),
                onContainer = pair.onContainer,
                size = 130.dp,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 22.dp, y = (-22).dp),
            )
            Column(Modifier.padding(18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Inverted tile: the one element that must read as an app icon, against a field of
                    // the same hue.
                    Box(
                        Modifier.size(52.dp).clip(tileShape(index)).background(pair.onContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        TemplateGlyph(
                            iconId = item.iconId,
                            size = 26.dp,
                            fallbackTint = pair.container,
                            forceTint = pair.container,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            sparseBadge(index, isRecent)?.let { badge ->
                                Surface(
                                    shape = RoundedCornerShape(7.dp),
                                    color = pair.onContainer,
                                    contentColor = pair.container,
                                    modifier = Modifier.height(22.dp),
                                ) {
                                    Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                                        Text(badge, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (postedLabel != null) {
                                SupportingOnContainer(postedLabel, pair.onContainer)
                            }
                        }
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.4).sp,
                            ),
                            color = pair.onContainer,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        SupportingOnContainer(
                            text = listOfNotNull(item.author, item.language).joinToString(" · ")
                                .ifBlank { item.category },
                            onContainer = pair.onContainer,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                // The first sentence only, truncated server-side so every client agrees where it ends.
                (item.blurb ?: item.summary).takeIf { it.isNotBlank() }?.let { blurb ->
                    Text(
                        blurb,
                        style = MaterialTheme.typography.bodyMedium,
                        color = pair.onContainer.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (item.tags.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        item.tags.take(3).forEach {
                            MonoChip(
                                it,
                                container = Color.Black.copy(alpha = 0.10f),
                                content = pair.onContainer,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RatingLine(item, pair.onContainer)
                    Spacer(Modifier.weight(1f))
                    Surface(
                        onClick = onAction,
                        shape = CircleShape,
                        color = pair.onContainer,
                        contentColor = pair.container,
                        modifier = Modifier.height(38.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The rating footer.
 *
 * "Not rated yet" is an actual text node, never a visual substitution over a hidden zero — a screen
 * reader must hear the same thing the eye sees.
 */
@Composable
private fun RatingLine(item: UiStoreItem, onContainer: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (item.rating >= 0f && item.ratingCount > 0) {
            Symbol(CaSymbols.star, contentDescription = null, size = 15.dp, filled = true, tint = onContainer)
            Text(
                "${formatRating(item.rating)} (${item.ratingCount})",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = onContainer,
            )
        } else {
            Text(
                "Not rated yet",
                style = MaterialTheme.typography.labelLarge,
                color = onContainer.copy(alpha = 0.75f),
            )
        }
        if (item.installs >= 0) {
            SupportingOnContainer("· ${installsLabel(item.installs)}", onContainer)
        }
    }
}

/**
 * Position-derived badges, per the handoff: the server stays out of presentational decisions.
 *
 * Index 0 is the shelf's first project; index 1 gets "NEW" only when it is genuinely recent.
 */
private fun sparseBadge(index: Int, isRecent: Boolean): String? = when {
    index == 0 -> "FIRST ON THE SHELF"
    index == 1 && isRecent -> "NEW"
    else -> null
}

/**
 * The publish argument.
 *
 * Scarcity as an offer, not a plea: it converts the page's weakness into the publisher's advantage and
 * puts a deadline on it. Deliberately not "help us grow the store" — that asks for a favour and gives
 * nothing back.
 */
@Composable
fun PublishPitchBand(
    projectCount: Int,
    onPublish: () -> Unit,
    onHowItWorks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 12.dp, bottomEnd = 32.dp, bottomStart = 32.dp),
        color = c.primaryContainer,
        contentColor = c.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Box(Modifier.clipToBounds()) {
            Symbol(
                CaSymbols.upload,
                contentDescription = null,
                size = 140.dp,
                tint = c.onPrimaryContainer.copy(alpha = 0.13f),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 20.dp, y = 26.dp),
            )
            Column(Modifier.padding(20.dp)) {
                Eyebrow("Shelves are still short", color = c.onPrimaryContainer.copy(alpha = 0.75f))
                Text(
                    pitchHeadline(projectCount),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = c.onPrimaryContainer,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Everything published right now sits on this page — no ranking to climb, no back " +
                        "pages to fall into. That stops being true later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onPrimaryContainer.copy(alpha = 0.82f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        onClick = onPublish,
                        shape = RoundedCornerShape(topStart = 15.dp, topEnd = 24.dp, bottomEnd = 15.dp, bottomStart = 24.dp),
                        color = c.onPrimaryContainer,
                        contentColor = c.primaryContainer,
                        modifier = Modifier.height(44.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Symbol(CaSymbols.upload, contentDescription = null, size = 19.dp)
                            Text("Publish a project", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Surface(
                        onClick = onHowItWorks,
                        shape = CircleShape,
                        color = Color.Transparent,
                        contentColor = c.onPrimaryContainer,
                        border = androidx.compose.foundation.BorderStroke(1.dp, c.onPrimaryContainer),
                        modifier = Modifier.height(44.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                            Text("How it works", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                // Two perks only. The founding-publisher badge was cut rather than promised: both of
                // these are simply true in a sparse store and need no durable commitment.
                Row(
                    Modifier.fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PerkTile(CaSymbols.visibility, "Front page from day one", Modifier.weight(1f))
                    PerkTile(CaSymbols.forum, "Direct line to the review team", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PerkTile(glyph: Char, label: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Symbol(glyph, contentDescription = null, size = 19.dp, tint = c.onPrimaryContainer)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = c.onPrimaryContainer,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * The pitch headline, with a computed ordinal.
 *
 * Above five the ordinal starts sounding like a small club rather than an opportunity, so it switches to
 * "Yours would be next" — the handoff's own note on the copy.
 */
internal fun pitchHeadline(count: Int): String {
    val subject = if (count == 1) "One project in." else "$count projects in."
    val ordinal = when (count) {
        1 -> "second"
        2 -> "third"
        3 -> "fourth"
        4 -> "fifth"
        else -> null
    }
    return if (ordinal != null) "$subject Yours would be the $ordinal." else "$subject Yours would be next."
}

/**
 * A ghost shelf: dashed, with a have/need counter.
 *
 * **Dashed means empty; shimmer means loading.** There is no shimmer anywhere in these states — an
 * animated skeleton on a store that will not fill without a publisher is a lie. The note names the
 * threshold rather than a date.
 */
@Composable
fun GhostShelfCard(
    /**
     * The progress counter, or null to omit it.
     *
     * Null is the empty state: `0 / 10` invites the reader to measure a distance, and at zero the honest
     * message is the condition that fills the shelf, not how far away it is.
     */
    shelf: UiGhostShelf?,
    title: String,
    note: String,
    glyph: Char,
    slotHeight: Dp,
    slotCount: Int,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .dashedBorder(c.outlineVariant, cornerRadius = 26.dp, strokeWidth = 1.dp)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol(glyph, contentDescription = null, size = 19.dp, tint = c.outlineVariant)
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = c.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (shelf != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = c.surfaceContainer,
                    contentColor = c.onSurfaceVariant,
                    modifier = Modifier.height(24.dp).semantics {
                        contentDescription = "${shelf.have} of ${shelf.need} projects needed"
                    },
                ) {
                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("${shelf.have} / ${shelf.need}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        // Flat slot rectangles at the real shelf's proportions, so the page's future shape is legible.
        // Cleared from semantics: a screen reader must not hear "image, image, image" — the note carries
        // the meaning.
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(slotCount) {
                Box(
                    Modifier.weight(1f).height(slotHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.surfaceContainer),
                )
            }
        }
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** A bundled offline scaffold. Works with no network, which is why an empty store still keeps this row. */
@Composable
fun BundledTemplateRow(
    title: String,
    meta: String,
    iconId: String,
    index: Int,
    onUse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onUse,
        shape = cardShape(index),
        color = c.surfaceContainerLow,
        contentColor = c.onSurface,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TemplateIcon(iconId, tonalPair(index), tileShape(index), size = 46.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Surface(shape = CircleShape, color = c.primaryContainer, contentColor = c.onPrimaryContainer) {
                Box(Modifier.height(34.dp).padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
                    Text("Use", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
