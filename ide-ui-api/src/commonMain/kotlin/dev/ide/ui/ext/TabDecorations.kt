package dev.ide.ui.ext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.icons.IconTint

/**
 * The editor tab strip's status dot, as a contribution.
 *
 * A tab already draws one dot: amber while the file has unsaved edits. This registry lets a plugin claim that
 * slot for a state of its own (the built-in contributions turn it red while a file's analysis left errors
 * behind, and cyan while a file changed on disk under an unsaved tab), so "this tab needs attention" is
 * answerable without opening it.
 *
 * The tab strip is host-drawn, so a decoration is data ([TabDecoration]: a themed tint, a shape, a label),
 * not a `@Composable` body. Its producer, however, IS composable: it reads whatever state it wants (a
 * `StateFlow` collected as state, its own observable store, or the diagnostics on [TabDecorationContext])
 * and the strip re-decorates when those reads change, with no polling and no push channel into the UI.
 */

/**
 * One open tab, as a decoration producer sees it.
 *
 * [diagnostics] is the tab's last analysis result. Every open tab is analyzed (on open, and again when
 * indexing or a build settles), but a tab is not re-analyzed while it sits in the background, so its
 * diagnostics are as old as the last of those events.
 */
interface TabDecorationContext {
    /** The tab's workspace path, or a `library://…` path for a read-only library tab. */
    val path: String

    /** The file name the tab shows. */
    val name: String

    /** Whether this is the focused tab. */
    val active: Boolean

    /** Whether the tab has unsaved edits, which is what the built-in amber dot reports. */
    val modified: Boolean

    /** Whether the file changed on disk while this tab held unsaved edits, so the host declined to reload it
     *  rather than clobber the edits (see `IdeUiState.syncOpenTabsFromDisk`). */
    val staleOnDisk: Boolean

    /** The diagnostics currently anchored to the tab's buffer (see the note on this interface). */
    val diagnostics: List<UiDiagnostic>

    /** How many of [diagnostics] are errors. */
    val errorCount: Int

    /** How many of [diagnostics] are warnings. */
    val warningCount: Int

    /** Everything the engine exposes to the UI, for a producer whose state lives behind it (a build's
     *  diagnostics, version control, a plugin's own backend service). */
    val backend: IdeBackend
}

/** How a decoration's dot is drawn. The two shapes carry the urgency: something to act on now is filled, a
 *  standing property of the file is a ring. */
enum class TabDotStyle { Filled, Outlined }

/**
 * How a decorated tab draws: a dot in [tint] and [style], described to accessibility (and to a desktop
 * tooltip) by [description].
 *
 * [tint] is a themed token rather than a raw color so a decoration follows the active theme and stays legible
 * in both light and dark; `IconTint.Fixed` is there for a decoration whose color carries its own meaning.
 */
class TabDecoration(
    val tint: IconTint,
    val description: String? = null,
    val style: TabDotStyle = TabDotStyle.Filled,
)

/**
 * A per-tab decoration producer. [decorate] returns the decoration for one tab, or null to leave the tab
 * alone.
 *
 * It runs inside the tab strip's composition, once per open tab, on every recomposition the strip performs,
 * so it must only read state and decide. Work belongs where the state is produced: subscribe on the engine
 * side (`IdeEventTopics.ANALYSIS` carries a file's merged diagnostics), keep the result in an observable
 * store, and read that store here.
 *
 * [order] sorts producers, low first; [id] breaks ties and identifies the producer in composition. The
 * built-ins occupy 50 (changed on disk), 100 (analysis errors and warnings) and 120 (build errors).
 */
class TabDecorationContribution(
    val id: String,
    val order: Int = 1000,
    val decorate: @Composable (TabDecorationContext) -> TabDecoration?,
)

/** The process-global registry of tab decorations. Compose-observable, like the rest of `dev.ide.ui.ext`:
 *  registering or disposing a contribution re-decorates the open tabs. */
object TabDecorationRegistry {
    private val ordering = compareBy<TabDecorationContribution>({ it.order }, { it.id })

    // Held in resolution order rather than sorted on read: [decorationFor] runs once per open tab on every
    // recomposition of the strip, and a registration is rare by comparison.
    private val items = mutableStateListOf<TabDecorationContribution>()

    fun register(decoration: TabDecorationContribution): Registration {
        val after = items.indexOfFirst { ordering.compare(it, decoration) > 0 }
        items.add(if (after < 0) items.size else after, decoration)
        return Registration { items.remove(decoration) }
    }

    /** Every registered producer, in resolution order: by [TabDecorationContribution.order], then by id. */
    fun all(): List<TabDecorationContribution> = items.toList()

    /**
     * The decoration to draw for [ctx]: the first non-null result in resolution order, since one tab has one
     * dot.
     *
     * Every producer is asked, even once one has claimed the slot, so the number of composable calls here
     * does not depend on their results. Each is keyed by its id, so adding or disposing a contribution
     * leaves the others' composition state intact.
     */
    @Composable
    fun decorationFor(ctx: TabDecorationContext): TabDecoration? {
        var winner: TabDecoration? = null
        for (contribution in items) {
            val decoration = key(contribution.id) { contribution.decorate(ctx) }
            if (winner == null) winner = decoration
        }
        return winner
    }
}
