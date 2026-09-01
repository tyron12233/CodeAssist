package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiStoreCollection
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStorePublisher
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.TonalPair
import dev.ide.ui.theme.cardShape
import dev.ide.ui.theme.tileShape
import dev.ide.ui.theme.tonalPair

/**
 * Collections, the personalized poster row, and the publisher spotlight.
 *
 * All three are shelves that only appear once the store has enough content to fill them honestly — the
 * server decides that, so nothing here has to reason about thresholds.
 */

/**
 * The tonal rotation for Collections starts on **tertiary** rather than primary.
 *
 * Deliberate: the Top-charts card immediately above is `surfaceContainerLow`, and starting the
 * collections row on plum gives that boundary a colour change instead of two quiet surfaces meeting.
 */
@Composable
fun collectionPair(index: Int): TonalPair = tonalPair(index + 2)

/**
 * A 246 dp editorial card.
 *
 * The footer's **overlapping icon stack** is the card's signature: three tiles pulled together with a
 * negative offset, each ringed in the card's own background colour so they read as a stack rather than a
 * row that has collided.
 */
@Composable
fun CollectionCard(
    collection: UiStoreCollection,
    index: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pair = collectionPair(index)
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        shape = cardShape(index),
        color = pair.container,
        contentColor = pair.onContainer,
        interactionSource = interaction,
        modifier = modifier.width(246.dp).pressScale(interaction),
    ) {
        Box(Modifier.clipToBounds()) {
            WatermarkGlyph(
                glyph = collection.iconId?.let { CaSymbols.forIconId(it) } ?: CaSymbols.folder,
                onContainer = pair.onContainer,
                size = 110.dp,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 20.dp, y = (-18).dp),
            )
            Column(Modifier.padding(18.dp)) {
                Eyebrow(collection.eyebrow, color = pair.onContainer.copy(alpha = 0.75f))
                Text(
                    collection.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.4).sp,
                    ),
                    color = pair.onContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconStack(collection.previewIconIds, pair)
                    Spacer(Modifier.weight(1f))
                    SupportingOnContainer(
                        text = if (collection.projectCount == 1) "1 project" else "${collection.projectCount} projects",
                        onContainer = pair.onContainer,
                    )
                }
            }
        }
    }
}

/** Three overlapping tiles, each ringed in the card's background so the overlap reads as depth. */
@Composable
private fun IconStack(iconIds: List<String>, pair: TonalPair) {
    Row {
        iconIds.take(3).forEachIndexed { i, id ->
            Box(
                Modifier
                    // Each tile after the first slides back over its predecessor.
                    .offset(x = (-10 * i).dp)
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(pair.container)
                    .border(2.dp, pair.container, RoundedCornerShape(11.dp))
                    .padding(2.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(pair.onContainer.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(CaSymbols.forIconId(id), contentDescription = null, size = 17.dp, tint = pair.onContainer)
            }
        }
    }
}

/**
 * A 146 dp poster for the personalized row.
 *
 * Art block on top, name and rating below — the shape that lets a row of these read as a shelf of things
 * rather than a list of rows. The language badge is inverted against the art so it stays legible on any
 * of the three tonal colours.
 */
@Composable
fun PosterCard(
    item: UiStoreItem,
    index: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    // +1 so a poster row never starts on the same tint as the shelf above it.
    val pair = tonalPair(index + 1)
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.width(146.dp).clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onOpen,
        ).pressScale(interaction),
    ) {
        Box(
            Modifier.fillMaxWidth().height(104.dp)
                .clip(cardShape(index + 1))
                .background(pair.container),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                CaSymbols.forIconId(item.iconId),
                contentDescription = null,
                size = 42.dp,
                tint = pair.onContainer,
            )
            item.language?.let { lang ->
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = pair.onContainer,
                    contentColor = pair.container,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).height(22.dp),
                ) {
                    Box(Modifier.padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                        Text(lang.uppercase(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 19.sp),
            color = c.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (item.rating >= 0f) {
                Symbol(CaSymbols.star, contentDescription = null, size = 13.dp, filled = true, tint = c.primary)
                Text(
                    formatRating(item.rating),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = c.onSurface,
                )
            } else {
                // Never a fabricated default: an unrated project says so.
                Text("Not rated yet", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            }
            if (item.sizeBytes() > 0) {
                Text(
                    "· ${formatSizeShort(item.sizeBytes())}",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The publisher spotlight: a full-width tonal card.
 *
 * The Follow button's state is **hoisted** — it is shared with the publisher profile screen, and the
 * handoff is explicit that following here must immediately reflect there. This composable therefore owns
 * none of it.
 */
@Composable
fun SpotlightCard(
    publisher: UiStorePublisher,
    following: Boolean,
    onOpen: () -> Unit,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 12.dp, bottomEnd = 32.dp, bottomStart = 32.dp),
        color = c.secondaryContainer,
        contentColor = c.onSecondaryContainer,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp).pressScale(interaction, pressed = 0.99f),
    ) {
        Box(Modifier.clipToBounds()) {
            Symbol(
                CaSymbols.apartment,
                contentDescription = null,
                size = 140.dp,
                tint = c.onSecondaryContainer.copy(alpha = 0.12f),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 18.dp, y = 26.dp),
            )
            Column(Modifier.padding(20.dp)) {
                Eyebrow("Publisher spotlight", color = c.onSecondaryContainer.copy(alpha = 0.75f))
                Row(
                    Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(18.dp))
                            .background(c.onSecondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Symbol(
                            CaSymbols.apartment,
                            contentDescription = null,
                            size = 26.dp,
                            tint = c.secondaryContainer,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                publisher.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 18.sp),
                                color = c.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (publisher.verified) {
                                Symbol(
                                    CaSymbols.verified,
                                    contentDescription = "Verified publisher",
                                    size = 16.dp,
                                    tint = c.onSecondaryContainer,
                                )
                            }
                        }
                        SupportingOnContainer(
                            text = publisherStats(publisher),
                            onContainer = c.onSecondaryContainer,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                publisher.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                    Text(
                        bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSecondaryContainer.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                FollowButton(
                    following = following,
                    onClick = onToggleFollow,
                    container = c.onSecondaryContainer,
                    content = c.secondaryContainer,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

/** Inverted-filled when not following, outlined once following — so the "done" state is the quieter one. */
@Composable
internal fun FollowButton(
    following: Boolean,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (following) Color.Transparent else container,
        contentColor = if (following) container else content,
        border = if (following) androidx.compose.foundation.BorderStroke(1.dp, container) else null,
        modifier = modifier.height(38.dp),
    ) {
        Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Text(
                if (following) "Following" else "Follow",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun publisherStats(p: UiStorePublisher): String = listOfNotNull(
    if (p.projectCount == 1) "1 project" else "${p.projectCount} projects",
    p.installCount.takeIf { it > 0 }?.let { installsLabel(it) },
    p.rating?.let { "★ ${formatRating(it)}" },
).joinToString(" · ")

/** `12.4 MB`, or `840 KB` below a megabyte. */
internal fun formatSizeShort(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) {
        val tenths = (mb * 10).toLong()
        return "${tenths / 10}.${tenths % 10} MB"
    }
    return "${(bytes / 1024).coerceAtLeast(1)} KB"
}

/** The download size, which lives on the detail DTO rather than the card one. */
private fun UiStoreItem.sizeBytes(): Long = downloadBytes
