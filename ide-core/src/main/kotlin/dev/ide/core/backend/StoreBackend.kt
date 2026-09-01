package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.model.template.ProjectTemplate
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.UiStoreCatalog
import dev.ide.ui.backend.UiStoreInstallResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiInstallProgress
import dev.ide.ui.backend.UiInstallState
import dev.ide.ui.backend.UiSignInPhase
import dev.ide.ui.backend.UiStoreAccount
import dev.ide.ui.backend.UiStoreAuthState
import dev.ide.ui.backend.UiStoreFeed
import dev.ide.ui.backend.UiStoreSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [StoreService] for the home screen's Explore tab.
 *
 * Two sources, and the precedence between them is the whole design:
 *
 *  1. The **bundled** [ProjectTemplate]s. Always present, always work offline, and the only content a
 *     build with no store endpoint has.
 *  2. The **remote** catalog, via [source]. When reachable it supplies the server-driven feed — modes,
 *     shelf ordering, charts — and *overlays* the bundled items by id rather than duplicating them.
 *
 * A remote failure is never an error state here. [feed] returns null and the caller falls back to
 * [catalog]'s bundled shelves, because "we cannot reach the store" and "nobody has published anything"
 * are opposite claims and must not render the same screen.
 *
 * The last good feed is cached on disk, so a cold launch offline shows content immediately instead of a
 * spinner.
 */
internal class StoreBackend(
    private val ctx: BackendContext,
    private val source: dev.ide.store.StoreCatalogSource = dev.ide.store.StoreCatalogSource.Unconfigured,
    private val accounts: dev.ide.store.StoreAccountService = dev.ide.store.StoreAccountService.Unsupported,
    private val submissions: dev.ide.store.StoreSubmissionService =
        dev.ide.store.StoreSubmissionService.Unsupported,
    /** Told when a submission's review state changes. Null in tests and on hosts with no storage. */
    private val notifications: NotificationCenter? = null,
) : StoreService {

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

    /**
     * The server-driven Explore feed, or null when there is no remote store to ask.
     *
     * Order of attempts: network, then the on-disk cache. A cached feed is marked [UiStoreFeed.fromCache]
     * so the UI can say so rather than presenting stale ranks as live.
     */
    override suspend fun feed(seedItemId: String?): UiStoreFeed? {
        if (!source.configured()) return null
        return withContext(dev.ide.core.backend.storeIo) {
            val bundled = bundledBySlug()
            when (val result = source.feedDocument(seedItemId)) {
                is dev.ide.store.StoreResult.Ok -> {
                    val parsed = dev.ide.store.impl.StoreFeedParser.parse(result.value)
                    if (parsed == null) {
                        // A response we cannot read is not evidence about the store, so behave as offline.
                        cachedFeed(bundled)
                    } else {
                        // Cache the exact bytes that were just rendered, so the cached copy cannot drift.
                        writeCache(result.value)
                        // Remember the payload coordinates so install() needs no second round trip.
                        rememberPayloads(parsed)
                        StoreFeedMapper.toUi(parsed, bundled)
                    }
                }
                // Offline or a server hiccup: fall back to whatever was last seen.
                else -> cachedFeed(bundled)
            }
        }
    }

    /**
     * Count one install, deduplicated server-side by the anonymous install id.
     *
     * Never throws, but it does **block** on a short HTTP POST, so it has to be called from a background
     * context. [install] does the counting itself, from the IO context, which is the only place it should
     * happen: the count belongs to a finished install, not to a tap.
     */
    override fun recordInstall(id: String) {
        if (!source.configured()) return
        val installId = ctx.manager?.preference(INSTALL_ID_PREF) ?: return
        runCatching { source.recordInstall(id, installId) }
    }

    // ---- accounts ----
    //
    // Delegated: sign-in state depends on the account port and nothing else in the IDE, so it lives in a
    // class that can be built and tested without a project, an engine or a host.

    private val accountState = StoreAccounts(accounts)

    override fun authProviders(): List<String> = accountState.authProviders()

    override fun authState(): kotlinx.coroutines.flow.StateFlow<UiStoreAuthState> = accountState.authState()

    override fun beginSignIn(provider: String): String? = accountState.beginSignIn(provider)

    override fun completeSignIn(redirect: String) = accountState.completeSignIn(redirect)

    override fun signOut() = accountState.signOut()

    // ---- submitting ----

    private val submissionState = StoreSubmissions(submissions)

    override fun submissionsAvailable(): Boolean = submissionState.available()

    override suspend fun packProject(rootPath: String): dev.ide.ui.backend.UiPackagedProject? =
        withContext(storeIo) { submissionState.pack(rootPath) }

    override suspend fun packFailure(rootPath: String): String? = submissionState.packError(rootPath)

    override suspend fun submitCategories(): List<Pair<String, String>> = withContext(storeIo) {
        when (val result = source.categories()) {
            is dev.ide.store.StoreResult.Ok -> result.value
            // No list means the form cannot offer a valid slug, so it offers none rather than guessing.
            else -> emptyList()
        }
    }

    override suspend fun submit(
        draft: dev.ide.ui.backend.UiSubmissionDraft,
        packaged: dev.ide.ui.backend.UiPackagedProject,
    ): dev.ide.ui.backend.UiSubmitResult = withContext(storeIo) {
        val result = submissionState.submit(draft, packaged)
        // Keyed on the item and version, so re-submitting the same version updates the entry rather than
        // stacking a second one, and a later review decision replaces this with its outcome.
        result.submission?.let { sub ->
            notifications?.post(
                kind = dev.ide.ui.backend.UiNotificationKind.STORE_SUBMISSION,
                title = "${draft.title} is in review",
                body = "A moderator reviews every submission. Nothing is public until it is approved.",
                target = dev.ide.ui.backend.UiNotificationTarget.Submissions,
                key = "submission:${sub.itemId}:${sub.version}",
            )
        }
        result
    }

    /**
     * The account's submissions, and a notification for anything whose state changed since last time.
     *
     * The change has to be noticed here because there is nothing to push it: a review happens on someone
     * else's schedule, days later, with the app closed. Comparing on each read is what turns that into
     * something the user finds out about at all.
     */
    override suspend fun mySubmissions(): List<dev.ide.ui.backend.UiStoreSubmission> = withContext(storeIo) {
        val current = submissionState.mine()
        current.forEach { sub ->
            val seenKey = "store.submission.seen.${sub.itemId}.${sub.version}"
            val previous = ctx.manager?.preference(seenKey)
            if (previous != sub.status.name) {
                ctx.manager?.setPreference(seenKey, sub.status.name)
                // The first sighting of a submission this device did not create is not news; only a change
                // from a state we had already recorded is.
                if (previous != null) notifications?.post(
                    kind = dev.ide.ui.backend.UiNotificationKind.STORE_SUBMISSION,
                    title = submissionHeadline(sub),
                    body = sub.note,
                    target = dev.ide.ui.backend.UiNotificationTarget.Submissions,
                    key = "submission:${sub.itemId}:${sub.version}",
                )
            }
        }
        current
    }

    /** What the change actually means, in the words a submitter would use. */
    private fun submissionHeadline(sub: dev.ide.ui.backend.UiStoreSubmission): String = when (sub.status) {
        dev.ide.ui.backend.UiSubmissionStatus.PUBLISHED -> "${sub.projectName} is live in the store"
        dev.ide.ui.backend.UiSubmissionStatus.REJECTED -> "${sub.projectName} was not accepted"
        dev.ide.ui.backend.UiSubmissionStatus.CHANGES_REQUESTED -> "${sub.projectName} needs changes"
        dev.ide.ui.backend.UiSubmissionStatus.BUILDING -> "${sub.projectName} is being built"
        dev.ide.ui.backend.UiSubmissionStatus.SUBMITTED -> "${sub.projectName} is in review"
    }

    override suspend fun withdrawSubmission(itemId: String, version: String): Boolean =
        withContext(storeIo) { submissionState.withdraw(itemId, version) }

    /**
     * The bundled catalog keyed by the id a remote row would use, for the overlay.
     *
     * Keys are the bare template id (`sample-calculator`), matching `store_items.slug`, NOT the
     * `sample:`/`template:`-prefixed id the bundled [catalog] emits for its own rows.
     */
    private fun bundledBySlug(): Map<String, UiStoreItem> =
        templates().associate { t ->
            t.id.value to toItem(t, if (isSample(t)) UiStoreItemKind.Sample else UiStoreItemKind.Template)
        }

    /**
     * The last good feed from disk.
     *
     * Returns null rather than an empty feed when nothing is cached: an empty feed would render the
     * "nobody has published anything" screen, which is a claim about the store rather than about the
     * network.
     */
    private fun cachedFeed(bundled: Map<String, UiStoreItem>): UiStoreFeed? {
        val file = cacheFile() ?: return null
        if (!file.isFile) return null
        val raw = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parsed = dev.ide.store.impl.StoreFeedParser.parse(raw) ?: return null
        // The cached rows are as good a source of payload coordinates as the live ones, and the sha256 is
        // still checked against the bytes: without this, an install from a cached feed would fail claiming
        // the item has nothing to download.
        rememberPayloads(parsed)
        return StoreFeedMapper.toUi(parsed, bundled).copy(fromCache = true)
    }

    private fun cacheFile(): java.io.File? =
        ctx.manager?.storageRoot?.let { java.io.File(it.toFile(), "store/explore-feed.json") }

    /** Best effort: a cache that cannot be written must not fail the fetch that produced it. */
    private fun writeCache(document: String) {
        val file = cacheFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(document)
        }
    }

    /**
     * Download, verify and unpack a community project into the workspace.
     *
     * Templates and samples never reach here: the UI routes anything with a `templateId` through the
     * Create-Project flow, because that scaffold is already on the device.
     *
     * Counting happens last, and only on success. Counting a tap instead would inflate the very numbers
     * the trending chart ranks on, with the items that fail to install ranking highest.
     */
    override suspend fun install(id: String, args: Map<String, String>): UiStoreInstallResult {
        val payload = payloads[id]
            ?: return UiStoreInstallResult(false, "That project has nothing to download yet")
        val projectsRoot = ctx.manager?.projectsRoot?.toFile()
            ?: return UiStoreInstallResult(false, "There is no projects folder to install into")

        val manager = ctx.manager
        // Everything here, the counting included, stays on the IO context: recordInstall performs a
        // blocking POST, and this is called from a UI scope, so counting outside would run it on the main
        // thread — where it throws, gets swallowed, and the install silently never counts.
        //
        // ProjectManager.list() rescans on every call, so the unpacked project is already visible to the
        // picker; the UI only has to be told to ask again (CodeAssistAppState.refreshProjects).
        return withContext(storeIo) {
            val result = installer.install(
                payload = payload,
                projectsRoot = projectsRoot,
                adopt = { dir ->
                    val ok = manager?.adoptProjectInPlace(dir.toPath()) ?: false
                    if (ok) null else "That download isn't a project CodeAssist can open"
                },
                onProgress = { p -> progressState.value = progressState.value + (p.itemId to p) },
            )
            if (result.success) recordInstall(id)
            result
        }
    }

    override fun installProgress(): kotlinx.coroutines.flow.StateFlow<Map<String, UiInstallProgress>> =
        progressState

    private val progressState =
        kotlinx.coroutines.flow.MutableStateFlow<Map<String, UiInstallProgress>>(emptyMap())

    private val installer = StoreInstaller(source)

    /**
     * Payload coordinates from the last feed, so an install needs no second round trip.
     *
     * Taken from the catalog row and never from the archive: the size and hash are what the server
     * promised, and checking the download against them is the point.
     */
    private val payloads = java.util.concurrent.ConcurrentHashMap<String, StoreInstaller.Payload>()

    private fun rememberPayloads(feed: dev.ide.store.StoreFeed) {
        feed.allItems.forEach { item ->
            val path = item.storagePath ?: return@forEach
            payloads[item.id] = StoreInstaller.Payload(item.id, path, item.sha256, item.sizeBytes, item.title)
        }
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

        /** The anonymous install id the analytics service already generates; reused for install dedupe. */
        const val INSTALL_ID_PREF = "analytics.install.id"

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
