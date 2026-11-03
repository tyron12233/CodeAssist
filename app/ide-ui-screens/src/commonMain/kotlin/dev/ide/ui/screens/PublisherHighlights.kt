package dev.ide.ui.screens

import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.components.isPluginListing
import kotlin.time.Instant

/**
 * What a publisher's page can say about their catalogue beyond listing it: which project stands out and why,
 * how to reorder the list, and how to split it when one publisher ships both apps and plugins.
 *
 * Pure functions over the items the profile already carries, so every claim the page makes ("most liked")
 * can be checked here without drawing anything.
 */

/** Why a project is singled out. Order is display order. */
internal enum class ProfileAward { MostInstalled, MostLiked, TopRated, Newest }

/** One card in the highlights row: a project, and every award it won, first award first. */
internal data class ProfileHighlight(val item: UiStoreItem, val awards: List<ProfileAward>)

/**
 * Fewest ratings a project needs before it can be called top rated. One five-star review from a friend is
 * not a rating anybody else should trust over forty fours.
 */
internal const val MIN_RATINGS_FOR_TOP = 3

/** Below this, `installsLabel` already says "New" rather than a number, so "most installed" would be noise. */
private const val MIN_INSTALLS_FOR_TOP = 10

/**
 * The awards a catalogue supports, merged per project.
 *
 * Every award is strict: a tie at the top names nobody, because "most liked" of two projects with three likes
 * each is a claim the data does not support. With a single project nothing is "most" anything, so a
 * one-project catalogue has no highlights at all. A project that wins several awards gets one card listing
 * them, rather than the same app appearing twice in a row of four.
 */
internal fun highlightsOf(items: List<UiStoreItem>): List<ProfileHighlight> {
    if (items.size < 2) return emptyList()
    val winners = linkedMapOf<String, MutableList<ProfileAward>>()
    fun award(item: UiStoreItem?, award: ProfileAward) {
        if (item != null) winners.getOrPut(item.id) { mutableListOf() } += award
    }
    award(strictMax(items.filter { it.installs >= MIN_INSTALLS_FOR_TOP }) { it.installs.toDouble() }, ProfileAward.MostInstalled)
    award(strictMax(items.filter { it.likes > 0 }) { it.likes.toDouble() }, ProfileAward.MostLiked)
    award(
        // A tie on the average goes to the one more people rated, which is the better-supported average.
        strictMax(items.filter { it.rating >= 0f && it.ratingCount >= MIN_RATINGS_FOR_TOP }) {
            it.rating * 10_000.0 + it.ratingCount.coerceAtMost(9_999)
        },
        ProfileAward.TopRated,
    )
    val dated = items.mapNotNull { item -> publishedMillis(item)?.let { item to it } }
    if (dated.size >= 2) {
        award(strictMax(dated) { it.second.toDouble() }?.first, ProfileAward.Newest)
    }
    val byId = items.associateBy { it.id }
    return winners.mapNotNull { (id, awards) -> byId[id]?.let { ProfileHighlight(it, awards.sorted()) } }
        .sortedBy { it.awards.first().ordinal }
}

/** The single largest by [score], or null when the top is shared or there is nothing to rank. */
private fun <T> strictMax(candidates: List<T>, score: (T) -> Double): T? {
    if (candidates.isEmpty()) return null
    val ranked = candidates.sortedByDescending(score)
    if (ranked.size > 1 && score(ranked[0]) == score(ranked[1])) return null
    return ranked[0]
}

/** How the list can be ordered. [Popular] is the server's own order, installs first. */
internal enum class ProfileSort { Popular, MostLiked, TopRated, Newest }

/**
 * [items] in [sort] order. Stable, so equal projects keep the server's popularity order between them, and
 * anything without the figure being sorted on (unrated, undated) goes after everything that has it.
 */
internal fun sortedForProfile(items: List<UiStoreItem>, sort: ProfileSort): List<UiStoreItem> = when (sort) {
    ProfileSort.Popular -> items
    ProfileSort.MostLiked -> items.sortedByDescending { it.likes.coerceAtLeast(0) }
    ProfileSort.TopRated -> items.sortedWith(
        compareByDescending<UiStoreItem> { it.rating >= 0f && it.ratingCount > 0 }
            .thenByDescending { it.rating }
            .thenByDescending { it.ratingCount },
    )
    ProfileSort.Newest -> items.sortedByDescending { publishedMillis(it) ?: Long.MIN_VALUE }
}

/** The sorts worth offering: ones that would actually reorder something, plus the default. */
internal fun usefulSorts(items: List<UiStoreItem>): List<ProfileSort> {
    if (items.size < 2) return emptyList()
    val useful = ProfileSort.entries.filter { it == ProfileSort.Popular || sortedForProfile(items, it) != items }
    return if (useful.size > 1) useful else emptyList()
}

/** Which shelf a project sits on when a publisher ships more than one kind of thing. */
internal enum class ProfileGroup { Projects, Plugins }

/**
 * The catalogue split into apps-and-projects and plugins, keeping each group's order.
 *
 * Only split when both groups have something: a single "Plugins" header over a plugin-only catalogue adds
 * a line and says nothing the icons do not. In that case the one group comes back with a null key.
 */
internal fun groupedForProfile(items: List<UiStoreItem>): List<Pair<ProfileGroup?, List<UiStoreItem>>> {
    val (plugins, projects) = items.partition { isPluginListing(it.category, it.tags) }
    if (plugins.isEmpty() || projects.isEmpty()) return listOf(null to items)
    return listOf(ProfileGroup.Projects to projects, ProfileGroup.Plugins to plugins)
}

/** The ISO 8601 publish time as epoch millis, or null when absent or unparseable. */
internal fun publishedMillis(item: UiStoreItem): Long? = isoMillis(item.publishedAt)

/** An ISO 8601 timestamp, as Postgres writes it into JSON, as epoch millis. */
internal fun isoMillis(iso: String?): Long? =
    iso?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
