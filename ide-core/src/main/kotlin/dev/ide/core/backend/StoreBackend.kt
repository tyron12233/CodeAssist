package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.model.template.ProjectTemplate
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.UiStoreCatalog
import dev.ide.ui.backend.UiStoreInstallResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [StoreService] for the home screen's Store tab. This is the UI-shell wiring: the catalog is served from the
 * bundled [ProjectTemplate]s (the real, installable content) plus a curated set of preview sample projects,
 * and the Community shelf is left empty. The interface is exactly what a remote, submission-backed catalog
 * will later implement, so the UI does not change when [catalog]/[search]/[install] move to a backend.
 *
 * Template items route through the normal Create-Project flow (the UI opens the configure form pre-selected
 * to [UiStoreItem.templateId]); only sample/community items would download through [install], which is not
 * wired yet (those items are marked [UiStoreItem.available] = false).
 */
internal class StoreBackend(private val ctx: BackendContext) : StoreService {

    private fun templates(): List<ProjectTemplate> =
        ctx.servicesOrNull?.projectTemplates() ?: ctx.manager?.projectTemplates() ?: emptyList()

    override fun storeAvailable(): Boolean = templates().isNotEmpty()

    override suspend fun catalog(): UiStoreCatalog = withContext(Dispatchers.Default) {
        val (sampleTemplates, starterTemplates) = templates().partition { isSample(it) }
        val starters = starterTemplates.map { toItem(it, UiStoreItemKind.Template) }
        val samples = sampleTemplates.map { toItem(it, UiStoreItemKind.Sample) }
        // Featured = the curated highlights, falling back to the first few starters so the carousel is
        // never empty on a host that contributes its own (uncurated) templates.
        val featured = starters.filter { it.featured }.ifEmpty { starters.take(3) }
        val categories = buildList {
            (starters + samples).map { it.category }.distinct().forEach(::add)
            add(CATEGORY_COMMUNITY)
        }
        val sections = listOf(
            UiStoreSection("templates", "Starter templates", "Spin up a new project from a curated scaffold", starters),
            UiStoreSection("samples", "Sample projects", "Complete, documented example apps you can build and run", samples),
            UiStoreSection("community", "Community", "Projects shared by the community", emptyList()),
        )
        UiStoreCatalog(featured = featured, categories = categories, sections = sections)
    }

    override suspend fun search(query: String, category: String?): List<UiStoreItem> = withContext(Dispatchers.Default) {
        val all = templates().map { toItem(it, if (isSample(it)) UiStoreItemKind.Sample else UiStoreItemKind.Template) }
        val q = query.trim().lowercase()
        all.filter { item -> matchesCategory(item, category) && matchesQuery(item, q) }
    }

    override suspend fun install(id: String, args: Map<String, String>): UiStoreInstallResult {
        // Templates AND samples are both created through the Create-Project flow (the UI routes any item with
        // a templateId there); only the future community downloads would land here.
        return UiStoreInstallResult(false, "Community projects are coming soon")
    }

    /** Sample projects are registered as `sample-`-prefixed templates so they share the create path but list
     *  under "Sample projects" rather than "Starter templates". */
    private fun isSample(t: ProjectTemplate): Boolean = t.id.value.startsWith("sample-")

    private fun matchesCategory(item: UiStoreItem, category: String?): Boolean = when (category) {
        null -> true
        CATEGORY_COMMUNITY -> item.kind == UiStoreItemKind.Community
        else -> item.category.equals(category, ignoreCase = true)
    }

    private fun matchesQuery(item: UiStoreItem, q: String): Boolean = q.isEmpty() ||
        item.title.lowercase().contains(q) ||
        item.summary.lowercase().contains(q) ||
        item.category.lowercase().contains(q) ||
        item.tags.any { it.lowercase().contains(q) }

    private fun toItem(t: ProjectTemplate, kind: UiStoreItemKind): UiStoreItem {
        val meta = CURATION[t.id.value]
        return UiStoreItem(
            id = "${if (kind == UiStoreItemKind.Sample) "sample" else "template"}:${t.id.value}",
            kind = kind,
            title = t.displayName,
            summary = t.description,
            category = t.category.displayName,
            iconId = t.iconId,
            tags = meta?.tags ?: listOf(t.category.displayName),
            featured = meta?.featured ?: false,
            accentColor = meta?.accent,
            installs = meta?.installs ?: -1,
            templateId = t.id.value,
            available = true,
            highlights = meta?.highlights ?: emptyList(),
            language = meta?.language ?: t.category.displayName.takeIf { it == "Java" || it == "Kotlin" },
            previewKey = t.id.value.takeIf { it in PREVIEW_SAMPLES },
        )
    }

    /** Per-template curation layered over the template's own metadata (featured flag, brand accent, tags,
     *  a soft usage count, "what you get" highlights, and the primary language) — all shown in the store. */
    private data class Curation(
        val featured: Boolean = false,
        val accent: Long? = null,
        val tags: List<String> = emptyList(),
        val installs: Int = -1,
        val highlights: List<String> = emptyList(),
        val language: String? = null,
    )

    private companion object {
        const val CATEGORY_COMMUNITY = "Community"

        /** Sample template ids that ship a built-in preview screenshot ([UiStoreItem.previewKey]). */
        val PREVIEW_SAMPLES = setOf("sample-snake", "sample-tictactoe", "sample-memory", "sample-2048")

        val CURATION: Map<String, Curation> = mapOf(
            "compose-app" to Curation(
                featured = true, accent = 0xFF3FBDD9, tags = listOf("Jetpack Compose", "Material 3", "Kotlin"), installs = 12800, language = "Kotlin",
                highlights = listOf("Jetpack Compose UI", "Material 3 theming", "A ready-to-run Activity", "Builds to an installable APK"),
            ),
            "android-material-you" to Curation(
                featured = true, accent = 0xFFB487F7, tags = listOf("Material You", "Views", "Kotlin"), installs = 6400, language = "Kotlin",
                highlights = listOf("Material You (dynamic color)", "XML layouts + Views", "A ready-to-run Activity"),
            ),
            "android-app" to Curation(
                featured = true, accent = 0xFF3DDC84, tags = listOf("Android", "Activity", "XML layouts"), installs = 21500, language = "Kotlin",
                highlights = listOf("An Activity + XML layout", "Resources wired up", "Builds to an installable APK"),
            ),
            "kotlin-console" to Curation(tags = listOf("Kotlin", "Console"), installs = 9300, language = "Kotlin", highlights = listOf("A top-level main()", "Full editor intelligence", "Runs in the console")),
            "java-console" to Curation(tags = listOf("Java", "Console"), installs = 8100, language = "Java", highlights = listOf("A main() entry point", "Full editor intelligence", "Runs in the console")),
            "android-library" to Curation(tags = listOf("Android", "AAR"), installs = 3200, language = "Kotlin", highlights = listOf("A reusable android-lib module", "Publishes as an AAR")),
            // Sample projects (complete, runnable examples).
            "sample-calculator" to Curation(
                accent = 0xFFF89820, tags = listOf("Java", "Parser", "REPL"), installs = 4200, language = "Java",
                highlights = listOf("An interactive read-eval-print loop", "A recursive-descent expression parser", "Operator precedence + parentheses", "No dependencies, thoroughly documented"),
            ),
            "sample-notes" to Curation(
                accent = 0xFF7F52FF, tags = listOf("Kotlin", "CRUD", "CLI"), installs = 5600, language = "Kotlin",
                highlights = listOf("A command loop: add/list/done/rm/find", "Reads input a line at a time", "Model split from view (testable)", "No dependencies, thoroughly documented"),
            ),
            "sample-weather" to Curation(
                accent = 0xFF3FBDD9, tags = listOf("Kotlin", "CLI", "Data"), installs = 3100, language = "Kotlin",
                highlights = listOf("Type a city, get its forecast", "Reads input in a loop", "Shows how to swap in a real API", "No dependencies"),
            ),
            // Jetpack Compose sample games (complete, runnable Compose apps).
            "sample-snake" to Curation(
                accent = 0xFF00E676, tags = listOf("Jetpack Compose", "Game", "Canvas"), installs = 7400, language = "Kotlin",
                highlights = listOf("Canvas rendering + a game loop", "Swipe gesture controls", "Live score + high score", "A neon Material 3 look"),
            ),
            "sample-tictactoe" to Curation(
                accent = 0xFF22D3EE, tags = listOf("Jetpack Compose", "Game", "Material 3"), installs = 5200, language = "Kotlin",
                highlights = listOf("Two-player game logic", "Animated marks + winning-line highlight", "State hoisting done right", "Material 3 theming"),
            ),
            "sample-memory" to Curation(
                accent = 0xFF7C3AED, tags = listOf("Jetpack Compose", "Game", "Animation"), installs = 4300, language = "Kotlin",
                highlights = listOf("3D card-flip animation", "Match logic + move/timer counters", "A colorful gradient UI", "A great intro to Compose animation"),
            ),
            "sample-2048" to Curation(
                accent = 0xFFEDC22E, tags = listOf("Jetpack Compose", "Game", "Puzzle"), installs = 6100, language = "Kotlin",
                highlights = listOf("Swipe-to-merge tile logic", "Animated tile colors", "Score + best tracking", "Clean grid-state modeling"),
            ),
        )
    }
}
