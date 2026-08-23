package dev.ide.android.support.icons

import dev.ide.android.support.preview.VectorSpec
import dev.ide.platform.ExtensionPoint

/**
 * An icon family's visual style. These are the Material Symbols families; a third-party repository maps its
 * own naming onto whichever of these fits, so the picker can offer one consistent style control.
 */
enum class IconStyle { OUTLINED, ROUNDED, SHARP }

/** A concrete rendering of an icon: its [style] family, filled or not. */
data class IconVariant(val style: IconStyle = IconStyle.OUTLINED, val filled: Boolean = false)

/**
 * One icon a repository offers: everything needed to list, search and label it, but *not* its geometry,
 * which a network repository would have to fetch per icon. [name] identifies the icon within its repository.
 */
data class IconEntry(
    val repositoryId: String,
    val name: String,
    val displayName: String,
    val keywords: List<String> = emptyList(),
    val category: String? = null,
    /** The style families this icon actually ships in. The picker greys out the rest. */
    val styles: Set<IconStyle> = setOf(IconStyle.OUTLINED),
    val supportsFill: Boolean = false,
)

/** An icon's resolved geometry, ready to preview and to write out as a VectorDrawable. */
data class IconArtwork(val spec: VectorSpec, val warnings: List<String> = emptyList())

/**
 * A source of icons the picker can browse: the bundled Material subset, a remote icon set, or anything a
 * plugin contributes. Registered through [ICON_REPOSITORY_EP].
 *
 * Implementations are **blocking**: the host calls [load] and [artwork] off the UI thread. A repository that
 * needs the network must report [requiresNetwork] so nothing is downloaded until the user asks for it.
 * [entries] then stays empty (and the UI offers to load) until [load] has succeeded once.
 */
interface IconRepository {

    /** Stable id, used in persisted state and in [IconEntry.repositoryId]. */
    val id: String

    val displayName: String

    /** The artwork licence, shown in the picker so an imported icon's terms are never a surprise. */
    val license: String

    /** Where the artwork comes from, for the same reason. */
    val attribution: String? get() = null

    /** True when [load] performs network IO; the picker then gates loading behind an explicit action. */
    val requiresNetwork: Boolean get() = false

    /** Every icon on offer, or empty when a network repository has not been loaded yet. */
    fun entries(): List<IconEntry>

    /** Populate [entries]. Called at most once per successful result; safe to call again after a failure. */
    fun load(): Result<Unit> = Result.success(Unit)

    /** [entry]'s geometry in the requested [variant], or null when it isn't available. */
    fun artwork(entry: IconEntry, variant: IconVariant = IconVariant()): IconArtwork?
}

/**
 * Icon repositories the Icon Manager browses. The host registers the built-in Material repositories; a
 * plugin can add its own set (Simple Icons, a company's brand library) without touching the picker.
 */
val ICON_REPOSITORY_EP = ExtensionPoint<IconRepository>("platform.iconRepository")

/**
 * Ranks icons against a search query the way the rest of the IDE's pickers do: an exact name match first,
 * then a name prefix, then a name substring, then a keyword or category hit. Kept out of [IconRepository] so
 * every repository, including plugin-contributed ones, searches identically.
 */
object IconSearch {

    /** [entries] matching [query], best first. A blank query keeps the repository's own order. */
    fun filter(entries: List<IconEntry>, query: String, limit: Int = Int.MAX_VALUE): List<IconEntry> {
        val q = query.trim().lowercase().replace(' ', '_')
        if (q.isEmpty()) return if (limit == Int.MAX_VALUE) entries else entries.take(limit)
        val scored = ArrayList<Pair<IconEntry, Int>>()
        for (e in entries) {
            val score = score(e, q)
            if (score > 0) scored += e to score
        }
        // Stable within a score band, so a repository's own popularity ordering survives ties.
        return scored.asSequence()
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
            .toList()
    }

    private fun score(entry: IconEntry, q: String): Int {
        val name = entry.name.lowercase()
        return when {
            name == q -> 100
            name.startsWith(q) -> 80
            name.split('_').any { it.startsWith(q) } -> 70
            name.contains(q) -> 60
            entry.keywords.any { it.equals(q, ignoreCase = true) } -> 50
            entry.keywords.any { it.contains(q, ignoreCase = true) } -> 30
            entry.category?.contains(q, ignoreCase = true) == true -> 20
            else -> 0
        }
    }
}
