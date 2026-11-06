package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.PillChip
import dev.ide.ui.components.SupportingOnContainer
import dev.ide.ui.components.formatRating
import dev.ide.ui.components.installsLabel
import dev.ide.ui.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiPublisherProfile
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.components.FollowButton
import dev.ide.ui.components.LinearProgressIndicator
import dev.ide.ui.components.formatStars
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.learn_section_title
import dev.ide.ui.generated.resources.profile_installs
import dev.ide.ui.generated.resources.profile_followers
import dev.ide.ui.generated.resources.profile_likes
import dev.ide.ui.generated.resources.profile_missing
import dev.ide.ui.generated.resources.profile_none
import dev.ide.ui.generated.resources.profile_projects
import dev.ide.ui.generated.resources.profile_published
import dev.ide.ui.generated.resources.profile_rating
import dev.ide.ui.generated.resources.profile_unrated
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.itemsIndexedKeyed
import dev.ide.ui.joinMeta
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A publisher's page: who they are, what they have published, and what it adds up to.
 *
 * The totals are the point. A single project's install count says little, while "three projects, 98k
 * installs, 4.6 across them" is the thing a reader is actually judging when they decide whether to trust
 * the next one.
 *
 * The rating shown is weighted by each project's rating count, computed server-side, so a project with one
 * five-star review cannot outweigh one with two hundred ratings.
 */
@Composable
fun PublisherProfileScreen(
    backend: IdeBackend,
    handle: String,
    onBack: () -> Unit,
    onOpenItem: (UiStoreItem) -> Unit,
    modifier: Modifier = Modifier,
    /** Asks for sign-in when following needs an account. Null hides the follow button entirely. */
    onNeedSignIn: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var following by remember(handle) { mutableStateOf<Boolean?>(null) }
    var followMessage by remember(handle) { mutableStateOf<String?>(null) }
    val signedIn = backend.store.authState().collectAsState().value.signedIn

    val profile by produceState<UiPublisherProfile?>(null, handle, backend) {
        value = UiPublisherProfile(handle, handle, loading = true)
        value = runCatching { backend.store.publisherProfile(handle) }.getOrNull()
    }
    val current = profile
    // What this person has learned. Null (nothing to show) leaves the section out entirely.
    val learning by produceState<dev.ide.ui.backend.UiLearnPublicProfile?>(null, handle, backend) {
        value = runCatching { backend.learn.publicProfile(handle) }.getOrNull()
    }
    var sort by remember(handle) { mutableStateOf(ProfileSort.Popular) }
    val sorts = remember(current?.items) { usefulSorts(current?.items.orEmpty()) }
    val now = remember(current) { dev.ide.ui.platform.nowMillis() }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            DetailTopBar(
                // The page's own header carries the name; the bar keeps only the way back.
                title = "",
                isSaved = false,
                onBack = onBack,
            )
            when {
                current == null -> Missing()
                current.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    item("head") {
                        ProfileHeader(
                            backend = backend,
                            profile = current,
                            // Local state wins once tapped, so the button reflects the tap rather than the
                            // fetch it was rendered from.
                            following = following ?: current.following,
                            onToggleFollow = {
                                if (!signedIn) {
                                    onNeedSignIn?.invoke()
                                } else {
                                    val next = !(following ?: current.following)
                                    following = next
                                    scope.launch {
                                        val error = backend.store.setFollowing(handle, next)
                                        if (error != null) {
                                            // Put it back: the server did not accept it.
                                            following = !next
                                            followMessage = error
                                        }
                                    }
                                }
                            },
                            showFollow = onNeedSignIn != null,
                            message = followMessage,
                            now = now,
                        )
                    }
                    current.error?.let { error ->
                        item("error") {
                            Text(
                                error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    val highlights = highlightsOf(current.items)
                    if (highlights.isNotEmpty()) {
                        item("highlights") {
                            Spacer(Modifier.height(28.dp))
                            ProfileSectionHeader(stringResource(Res.string.profile_highlights))
                            Spacer(Modifier.height(12.dp))
                            HighlightsRow(backend, highlights, now, onOpenItem)
                        }
                    }
                    learning?.let { l ->
                        item("learning") {
                            Spacer(Modifier.height(28.dp))
                            ProfileSectionHeader(stringResource(Res.string.learn_section_title))
                            Spacer(Modifier.height(12.dp))
                            LearnProfileCard(l.streak, l.bestStreak, l.lessonsStarted, l.badges, showEmptyBadges = false)
                        }
                    }
                    item("shelf") {
                        Spacer(Modifier.height(28.dp))
                        ProfileSectionHeader(stringResource(Res.string.profile_published), current.items.size)
                        if (sorts.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SortChips(sorts, sort) { sort = it }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (current.items.isEmpty()) {
                        item("empty") {
                            Text(
                                stringResource(Res.string.profile_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }
                    groupedForProfile(sortedForProfile(current.items, sort)).forEach { (group, items) ->
                        if (group != null) {
                            item("group_${group.name}") {
                                Spacer(Modifier.height(12.dp))
                                Eyebrow(
                                    stringResource(
                                        when (group) {
                                            ProfileGroup.Projects -> Res.string.profile_group_projects
                                            ProfileGroup.Plugins -> Res.string.profile_group_plugins
                                        },
                                    ) + "  ·  " + items.size,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                )
                            }
                        }
                        itemsIndexedKeyed(items, key = { _, it -> it.id }) { i, item ->
                            StoreItemRow(
                                item,
                                i,
                                onOpenItem,
                                backend = backend,
                                // The publisher is this page; the row says what the project is instead.
                                subtitle = (item.blurb ?: item.summary).ifBlank { item.category },
                                meta = profileItemMeta(item),
                            )
                        }
                    }
                    item("tail") { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    backend: IdeBackend,
    profile: UiPublisherProfile,
    following: Boolean,
    onToggleFollow: () -> Unit,
    showFollow: Boolean,
    message: String?,
    now: Long,
) {
    val c = MaterialTheme.colorScheme
    ProfileHero(
        backend = backend,
        displayName = profile.displayName,
        handle = profile.handle,
        avatarUrl = profile.avatarUrl,
        verified = profile.verified,
        bio = profile.bio,
        location = profile.location,
        linkUrl = profile.linkUrl,
        extraFacts = listOfNotNull(
            CaSymbols.person to stringResource(Res.string.profile_followers, profile.followers),
            isoMillis(profile.joinedAt)?.let { joined ->
                CaSymbols.schedule to stringResource(Res.string.profile_joined, relativeAgeLabel(joined, now))
            },
        ),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        if (showFollow) {
            FollowButton(
                following = following,
                onClick = onToggleFollow,
                container = c.primary,
                content = c.onPrimary,
                modifier = Modifier.height(40.dp),
            )
        }
    }
    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = c.error,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
    Spacer(Modifier.height(20.dp))
    ProfileStats(
        listOf(
            ProfileStat(CaSymbols.deployedCode, profile.projectCount.toString(), stringResource(Res.string.profile_projects)),
            ProfileStat(CaSymbols.download, compactCount(profile.totalInstalls), stringResource(Res.string.profile_installs)),
            ProfileStat(CaSymbols.thumbUp, compactCount(profile.totalLikes), stringResource(Res.string.profile_likes)),
            ProfileStat(
                CaSymbols.star,
                // Absent rather than 0.0: an unrated catalogue has no average, and zero would read as
                // unanimously terrible.
                profile.averageRating?.let { formatStars(it) } ?: "–",
                if (profile.averageRating != null) {
                    stringResource(Res.string.profile_rating)
                } else {
                    stringResource(Res.string.profile_unrated)
                },
            ),
        ),
    )
}

@Composable
private fun Missing() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.profile_missing),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/** The colours, glyph and label an award is drawn with. One container role per award, never invented. */
@Composable
private fun awardStyle(award: ProfileAward): Triple<dev.ide.ui.theme.TonalPair, Char, String> {
    val c = MaterialTheme.colorScheme
    return when (award) {
        ProfileAward.MostInstalled -> Triple(
            dev.ide.ui.theme.TonalPair(c.primaryContainer, c.onPrimaryContainer),
            CaSymbols.download,
            stringResource(Res.string.profile_award_installs),
        )
        ProfileAward.MostLiked -> Triple(
            dev.ide.ui.theme.TonalPair(c.tertiaryContainer, c.onTertiaryContainer),
            CaSymbols.thumbUp,
            stringResource(Res.string.profile_award_liked),
        )
        ProfileAward.TopRated -> Triple(
            dev.ide.ui.theme.TonalPair(c.secondaryContainer, c.onSecondaryContainer),
            CaSymbols.star,
            stringResource(Res.string.profile_award_rated),
        )
        ProfileAward.Newest -> Triple(
            dev.ide.ui.theme.TonalPair(c.surfaceContainerHighest, c.onSurface),
            CaSymbols.bolt,
            stringResource(Res.string.profile_award_newest),
        )
    }
}

/** The figure that earned [award], said the way the store says it everywhere else. */
@Composable
private fun awardFigure(item: UiStoreItem, award: ProfileAward, now: Long): String = when (award) {
    ProfileAward.MostInstalled -> installsLabel(item.installs)
    ProfileAward.MostLiked -> pluralStringResource(Res.plurals.store_likes, item.likes, compactCount(item.likes))
    ProfileAward.TopRated -> "${formatRating(item.rating)} ★"
    ProfileAward.Newest -> publishedMillis(item)?.let { relativeAgeLabel(it, now) }.orEmpty()
}

/**
 * The catalogue's standouts as a small leaderboard: one row per award, each naming its project and the
 * figure that won it.
 *
 * A table rather than a row of cards: one project often wins several awards, and as cards that made one
 * tall card of stacked labels beside a short one. As rows, a project that leads on three things simply
 * appears three times, which is what a leaderboard looks like, and every row is the same height.
 */
@Composable
private fun HighlightsRow(
    backend: IdeBackend,
    highlights: List<ProfileHighlight>,
    now: Long,
    onOpenItem: (UiStoreItem) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val rows = highlights.flatMap { h -> h.awards.map { it to h.item } }.sortedBy { it.first.ordinal }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = c.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            rows.forEachIndexed { i, (award, item) ->
                if (i > 0) {
                    Box(
                        Modifier.fillMaxWidth().padding(start = 76.dp, end = 16.dp).height(1.dp)
                            .background(c.outlineVariant),
                    )
                }
                AwardRow(backend, award, item, now) { onOpenItem(item) }
            }
        }
    }
}

@Composable
private fun AwardRow(backend: IdeBackend, award: ProfileAward, item: UiStoreItem, now: Long, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val (pair, glyph, label) = awardStyle(award)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The app icon, with the award as a badge on its corner: the project is what the row is about.
        Box(Modifier.size(46.dp)) {
            val icon = rememberItemIcon(backend, item)
            val iconModifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
            if (icon != null) icon(iconModifier) else Box(iconModifier.background(c.surfaceContainerHighest))
            Box(
                Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape)
                    .background(c.surfaceContainer).padding(2.dp)
                    .clip(CircleShape).background(pair.container),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(glyph, contentDescription = null, size = 12.dp, tint = pair.onContainer, filled = true)
            }
        }
        Column(Modifier.weight(1f)) {
            Eyebrow(label, color = c.onSurfaceVariant)
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            awardFigure(item, award, now),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = c.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SortChips(sorts: List<ProfileSort>, selected: ProfileSort, onSelect: (ProfileSort) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sorts.size, key = { sorts[it].name }) { i ->
            val sort = sorts[i]
            PillChip(
                label = stringResource(
                    when (sort) {
                        ProfileSort.Popular -> Res.string.profile_sort_popular
                        ProfileSort.MostLiked -> Res.string.profile_award_liked
                        ProfileSort.TopRated -> Res.string.profile_award_rated
                        ProfileSort.Newest -> Res.string.profile_award_newest
                    },
                ),
                selected = sort == selected,
                onClick = { onSelect(sort) },
            )
        }
    }
}

/**
 * A row's stat line on a publisher's page: `4.7 ★ · 693 installs · 6 likes`.
 *
 * The figures the page ranks by, rather than the store shelves' language and size. Only what is known:
 * an unrated project says nothing about rating, and a project nobody liked says nothing about likes.
 */
@Composable
private fun profileItemMeta(item: UiStoreItem): String = listOfNotNull(
    item.rating.takeIf { it >= 0f && item.ratingCount > 0 }?.let { "${formatRating(it)} ★" },
    item.installs.takeIf { it >= 0 }?.let { installsLabel(it) },
    item.likes.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.store_likes, it, compactCount(it)) },
).joinMeta()

/** "3 days ago", "2 months ago": how long ago [thenMs] was, coarsely. */
@Composable
private fun relativeAgeLabel(thenMs: Long, now: Long): String {
    val minutes = (now - thenMs).coerceAtLeast(0) / 60_000
    val days = (minutes / (60 * 24)).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.project_age_just_now)
        minutes < 60 -> pluralStringResource(Res.plurals.project_age_minutes, minutes.toInt(), minutes.toInt())
        minutes < 60 * 24 -> (minutes / 60).toInt().let { pluralStringResource(Res.plurals.project_age_hours, it, it) }
        days < 7 -> pluralStringResource(Res.plurals.project_age_days, days, days)
        days < 30 -> (days / 7).let { pluralStringResource(Res.plurals.project_age_weeks, it, it) }
        days < 365 -> (days / 30).let { pluralStringResource(Res.plurals.profile_age_months, it, it) }
        else -> (days / 365).let { pluralStringResource(Res.plurals.profile_age_years, it, it) }
    }
}
