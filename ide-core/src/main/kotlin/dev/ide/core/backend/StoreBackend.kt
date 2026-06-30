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
        val templateItems = templates().map(::toItem)
        // Featured = the curated highlights, falling back to the first few templates so the carousel is
        // never empty on a host that contributes its own (uncurated) templates.
        val featured = templateItems.filter { it.featured }.ifEmpty { templateItems.take(3) }
        val categories = buildList {
            templateItems.map { it.category }.distinct().forEach(::add)
            if (SAMPLE_ITEMS.isNotEmpty()) add(CATEGORY_SAMPLES)
            add(CATEGORY_COMMUNITY)
        }
        val sections = listOf(
            UiStoreSection("templates", "Starter templates", "Spin up a new project from a curated scaffold", templateItems),
            UiStoreSection("samples", "Sample projects", "Full example apps to read and run", SAMPLE_ITEMS),
            UiStoreSection("community", "Community", "Projects shared by the community", emptyList()),
        )
        UiStoreCatalog(featured = featured, categories = categories, sections = sections)
    }

    override suspend fun search(query: String, category: String?): List<UiStoreItem> = withContext(Dispatchers.Default) {
        val all = templates().map(::toItem) + SAMPLE_ITEMS
        val q = query.trim().lowercase()
        all.filter { item -> matchesCategory(item, category) && matchesQuery(item, q) }
    }

    override suspend fun install(id: String, args: Map<String, String>): UiStoreInstallResult {
        // Template items are created through the Create-Project flow (the UI routes them there with the
        // configure form), never here. Sample/community downloads are filled in by the remote-catalog seam.
        return UiStoreInstallResult(false, "Sample and community projects are coming soon")
    }

    private fun matchesCategory(item: UiStoreItem, category: String?): Boolean = when (category) {
        null -> true
        CATEGORY_SAMPLES -> item.kind == UiStoreItemKind.Sample
        CATEGORY_COMMUNITY -> item.kind == UiStoreItemKind.Community
        else -> item.category.equals(category, ignoreCase = true)
    }

    private fun matchesQuery(item: UiStoreItem, q: String): Boolean = q.isEmpty() ||
        item.title.lowercase().contains(q) ||
        item.summary.lowercase().contains(q) ||
        item.category.lowercase().contains(q) ||
        item.tags.any { it.lowercase().contains(q) }

    private fun toItem(t: ProjectTemplate): UiStoreItem {
        val meta = CURATION[t.id.value]
        return UiStoreItem(
            id = "template:${t.id.value}",
            kind = UiStoreItemKind.Template,
            title = t.displayName,
            summary = t.description,
            category = t.category.displayName,
            iconId = t.iconId,
            tags = meta?.tags ?: listOf(t.category.displayName),
            featured = meta?.featured ?: false,
            accentColor = meta?.accent,
            templateId = t.id.value,
            available = true,
        )
    }

    /** Per-template curation layered over the template's own metadata (featured flag, brand accent, tags). */
    private data class Curation(val featured: Boolean = false, val accent: Long? = null, val tags: List<String> = emptyList())

    private companion object {
        const val CATEGORY_SAMPLES = "Samples"
        const val CATEGORY_COMMUNITY = "Community"

        val CURATION: Map<String, Curation> = mapOf(
            "compose-app" to Curation(featured = true, accent = 0xFF3FBDD9, tags = listOf("Jetpack Compose", "Material 3", "Kotlin")),
            "android-material-you" to Curation(featured = true, accent = 0xFFB487F7, tags = listOf("Material You", "Views", "Kotlin")),
            "android-app" to Curation(featured = true, accent = 0xFF3DDC84, tags = listOf("Android", "Activity", "XML layouts")),
            "kotlin-console" to Curation(tags = listOf("Kotlin", "Console")),
            "java-console" to Curation(tags = listOf("Java", "Console")),
            "android-library" to Curation(tags = listOf("Android", "AAR")),
        )

        // Curated preview samples (browse-only until the remote catalog wires downloads). Marked unavailable
        // so the detail sheet shows "Coming soon" rather than a working install.
        val SAMPLE_ITEMS: List<UiStoreItem> = listOf(
            UiStoreItem(
                id = "sample:notes", kind = UiStoreItemKind.Sample, title = "Notes",
                summary = "A Material 3 notes app with a list/detail flow.",
                description = "A complete note-taking app: a Material 3 list/detail flow, swipe-to-delete, and " +
                    "local persistence. A good tour of state handling and navigation.",
                category = "Android", iconId = "module.android", author = "CodeAssist",
                tags = listOf("Material 3", "CRUD", "Navigation"), accentColor = 0xFF3DDC84, available = false,
            ),
            UiStoreItem(
                id = "sample:weather", kind = UiStoreItemKind.Sample, title = "Weather",
                summary = "Fetches a forecast over HTTP and renders it in Compose.",
                description = "A small Compose app that loads a forecast over HTTP and renders an hourly + daily " +
                    "view. Shows networking, loading/error states, and lists.",
                category = "Android", iconId = "module.android", author = "CodeAssist",
                tags = listOf("Compose", "Networking", "Coroutines"), accentColor = 0xFF3FBDD9, available = false,
            ),
            UiStoreItem(
                id = "sample:snake", kind = UiStoreItemKind.Sample, title = "Snake",
                summary = "A classic Snake game drawn on a Compose canvas.",
                description = "The classic Snake game: a game loop, gesture input, and Canvas drawing. A compact " +
                    "example of frame-driven UI.",
                category = "Games", iconId = "kotlin", author = "CodeAssist",
                tags = listOf("Canvas", "Game loop", "Kotlin"), accentColor = 0xFFB487F7, available = false,
            ),
            UiStoreItem(
                id = "sample:calc", kind = UiStoreItemKind.Sample, title = "Calculator",
                summary = "An expression-parsing calculator, console + tests.",
                description = "A Java console calculator with an expression parser and a JUnit test suite. A clean " +
                    "starting point for parsing and unit testing.",
                category = "Java", iconId = "java", author = "CodeAssist",
                tags = listOf("Parsing", "JUnit", "Console"), available = false,
            ),
        )
    }
}
