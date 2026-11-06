package dev.ide.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol

/**
 * The top of a profile page, shared by the account's own page and the public one so a publisher looks the
 * same in both: a tinted banner, the picture overlapping its foot, the name, the handle, and whatever the
 * page offers to do (Edit on your own, Follow on someone else's) on the banner's baseline.
 *
 * The banner's pair of containers is picked from the handle, so a publisher keeps the same colours
 * everywhere they appear and two publishers seen one after the other do not look like the same page.
 * Everything drawn on it is a container role and its own `on*` role; nothing is an invented colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileHero(
    backend: IdeBackend,
    displayName: String,
    handle: String,
    avatarUrl: String?,
    verified: Boolean,
    bio: String?,
    location: String?,
    linkUrl: String?,
    modifier: Modifier = Modifier,
    /** Extra facts for the chip row after location and link, as glyph and text: followers, joined. */
    extraFacts: List<Pair<Char, String>> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    val (from, to, glyphTint) = bannerColors(handle)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(BannerHeight + AvatarSize / 2)) {
            Box(
                Modifier.fillMaxWidth().height(BannerHeight)
                    .clip(BannerShape)
                    .background(Brush.linearGradient(listOf(from, to))),
            ) {
                // Watermark glyphs at the one sanctioned watermark step. What a publisher here makes is
                // code, so that is what the banner is patterned with.
                Symbol(
                    CaSymbols.codeBlocks, contentDescription = null, size = 132.dp,
                    tint = glyphTint.copy(alpha = 0.13f),
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 22.dp, y = (-18).dp).rotate(-14f),
                )
                Symbol(
                    CaSymbols.terminal, contentDescription = null, size = 54.dp,
                    tint = glyphTint.copy(alpha = 0.13f),
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-118).dp, y = 10.dp).rotate(10f),
                )
                Symbol(
                    CaSymbols.functions, contentDescription = null, size = 40.dp,
                    tint = glyphTint.copy(alpha = 0.13f),
                    modifier = Modifier.align(Alignment.TopCenter).offset(x = (-24).dp, y = 18.dp).rotate(-8f),
                )
            }
            ProfileAvatar(
                backend = backend,
                name = displayName,
                avatarUrl = avatarUrl,
                size = AvatarSize,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp),
            )
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (verified) {
                    Symbol(CaSymbols.verified, contentDescription = null, size = 22.dp, tint = c.primary, filled = true)
                }
            }
            Text("@$handle", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            bio?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
            }
            val facts = listOfNotNull(
                location?.takeIf { it.isNotBlank() }?.let { CaSymbols.explore to it },
                linkUrl?.takeIf { it.isNotBlank() }?.let { CaSymbols.link to it.removePrefix("https://") },
            ) + extraFacts
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    facts.forEach { (glyph, text) -> FactChip(glyph, text) }
                }
            }
        }
    }
}

private val BannerHeight = 124.dp
private val AvatarSize = 96.dp
private val BannerShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 12.dp)

/** Start, end and watermark colours for a handle's banner. Stable per handle, so a publisher keeps theirs. */
@Composable
private fun bannerColors(handle: String): Triple<Color, Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (handle.hashCode().mod(3)) {
        0 -> Triple(c.primaryContainer, c.tertiaryContainer, c.onPrimaryContainer)
        1 -> Triple(c.tertiaryContainer, c.primaryContainer, c.onTertiaryContainer)
        else -> Triple(c.secondaryContainer, c.primaryContainer, c.onSecondaryContainer)
    }
}

@Composable
private fun FactChip(glyph: Char, text: String) {
    val c = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = c.surfaceContainerHigh) {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Symbol(glyph, contentDescription = null, size = 16.dp, tint = c.primary)
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The provider's picture where there is one, and the initials it falls back to where there is not.
 *
 * Ringed in the page background so it reads as sitting on top of the banner rather than cut into it.
 */
@Composable
internal fun ProfileAvatar(
    backend: IdeBackend,
    name: String,
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val file by produceState<String?>(null, avatarUrl) {
        value = avatarUrl?.let { runCatching { backend.store.avatarFile(it) }.getOrNull() }
    }
    Surface(
        shape = CircleShape,
        color = c.primary,
        border = BorderStroke(4.dp, c.background),
        modifier = modifier.size(size),
    ) {
        val path = file
        if (path != null) {
            ShotImage(backend, path, ContentScale.Crop, Modifier.fillMaxSize().padding(4.dp).clip(CircleShape))
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    initialsOf(name),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = c.onPrimary,
                )
            }
        }
    }
}

/** At most two letters, from the first two words that have one. */
internal fun initialsOf(name: String): String =
    name.split(' ', '-', '_').mapNotNull { it.firstOrNull { ch -> ch.isLetterOrDigit() } }
        .take(2).joinToString("").uppercase().ifEmpty { "?" }

/** One figure in a [ProfileStats] card. */
internal data class ProfileStat(val glyph: Char, val value: String, val label: String)

/**
 * A profile's numbers as a two-by-two grid of tinted tiles.
 *
 * Each figure gets its own container role, so the four read as four different things at a glance rather
 * than one strip of equal columns, and the number is large and leading because it is what is being read.
 * The tile's glyph is repeated as a watermark at the one sanctioned 0.13 step.
 */
@Composable
internal fun ProfileStats(stats: List<ProfileStat>, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val pairs = listOf(
        c.primaryContainer to c.onPrimaryContainer,
        c.tertiaryContainer to c.onTertiaryContainer,
        c.secondaryContainer to c.onSecondaryContainer,
        c.surfaceContainerHighest to c.onSurface,
    )
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        stats.chunked(2).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { i, stat ->
                    val (container, content) = pairs[(r * 2 + i) % pairs.size]
                    StatTile(stat, container, content, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(stat: ProfileStat, container: Color, content: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(84.dp).clip(RoundedCornerShape(22.dp)).background(container)) {
        Symbol(
            stat.glyph, contentDescription = null, size = 72.dp, tint = content.copy(alpha = 0.13f), filled = true,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 14.dp),
        )
        Column(
            Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
        ) {
            Text(
                stat.value,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = content,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Symbol(stat.glyph, contentDescription = null, size = 14.dp, tint = content.copy(alpha = 0.7f))
                Text(
                    stat.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = content.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A section title with its count beside it, so a list says how long it is before it is scrolled. */
@Composable
internal fun ProfileSectionHeader(title: String, count: Int? = null, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = c.onSurface,
        )
        if (count != null && count > 0) {
            Surface(shape = CircleShape, color = c.secondaryContainer) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}
