package dev.ide.core

import dev.ide.core.settings.SettingChanged
import dev.ide.core.settings.SettingsListener
import dev.ide.core.settings.SettingsTopics
import dev.ide.model.ProjectId
import dev.ide.model.event.LibrariesChanged
import dev.ide.model.event.ProjectModelEvent
import dev.ide.model.event.ProjectModelListener
import dev.ide.model.event.ProjectModelTopics
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.ContentHash
import dev.ide.platform.MessageBusConnection
import dev.ide.vfs.FileChanged
import dev.ide.vfs.FileCreated
import dev.ide.vfs.FileDeleted
import dev.ide.vfs.FileMoved
import dev.ide.vfs.VfsEvent
import dev.ide.vfs.VfsListener
import dev.ide.vfs.VfsTopics
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * The workspace's change-notification spine. Every mutation the engine performs (an editor save, a file
 * create/delete/move/copy, a model commit (modules/dependencies/source sets/facets), a finished library
 * resolution, an SDK install, a settings change) is PUBLISHED here as a typed event on the app message
 * bus ([VfsTopics.CHANGES] / [ProjectModelTopics.CHANGES] / [SettingsTopics.CHANGES]), and every reaction
 * (analyzer invalidation, index re-sync, synthetic-class refresh, editor-overlay maintenance) runs as a
 * SUBSCRIBER of that stream. In-process consumers and the out-of-process engines' hint fan-out (the `:aa`
 * Kotlin Analysis API daemon, the `:build` daemon) observe ONE ordered stream instead of each mutation
 * site hand-calling its own invalidation chain.
 *
 * The bus is synchronous and delivers in subscription order, so routing the old inline chains through
 * here preserves their ordering and thread exactly; publishing a multi-file operation as one batch lets
 * the reaction coalesce (one analyzer invalidation for a 50-file package copy, not 50).
 *
 * Reaction table (matches, and where marked safely widens, the pre-hub inline behavior):
 *  - [FileChanged], single file (an editor save): drop the JDT binding caches; a `res/` file refreshes the
 *    synthetic classes (and re-indexes a `.xml`); a `.kt` refreshes the synthetic facades. Deliberately
 *    LIGHT; this is the hot path, and open-buffer overlays already make the new text visible.
 *  - [FileChanged] batch of 2+ (a refactoring's multi-file edit): the light per-file work, plus a full
 *    analyzer invalidation + index re-sync (a cross-file rename changes declared type names, which the
 *    cached name environments won't see through overlays alone).
 *  - [FileCreated]: a `res/` file refreshes synthetics (+ `.xml` re-index); a source file (.java/.kt)
 *    invalidates analyzers + re-syncs (a created-but-never-opened file (e.g. a copy) is invisible to the
 *    cached name environments otherwise; widened from the old create-file path, which relied on the file
 *    being opened); other files re-sync the index only; directories are no-ops.
 *  - [FileDeleted]: drop overlays under it, invalidate analyzers, refresh synthetics, re-sync.
 *  - [FileMoved]: re-key overlays, invalidate analyzers, refresh synthetics, re-sync.
 *  - model events (any commit): invalidate analyzers + refresh synthetics + re-sync + bump [configStamp].
 *    This is the subscriber that was MISSING pre-hub: a `DependenciesChanged` commit now invalidates
 *    without relying on the mutation site to remember to.
 *  - settings events: bump [configStamp]; an active-variant change (`variant.<module>`) additionally
 *    invalidates + re-syncs (it changes the variant-filtered classpath).
 */
internal class WorkspaceEventHub(
    private val store: ProjectModelStore,
    private val reactions: Reactions,
) : AutoCloseable {

    /** The engine's invalidation surface the hub reacts through. Implemented by [IdeServices] as an inner
     *  object so the hub never needs the god class (and the private helpers stay private). */
    internal interface Reactions {
        fun invalidateAnalyzers()
        fun invalidateSyntheticClasses()
        fun resyncIndex()

        /** Incrementally re-index one source file (async, off the caller's thread). */
        fun reindexSourceAsync(path: Path)

        /** Drop the JDT binding-parse caches on the live module analyzers (a saved edit can change how
         *  OTHER files resolve; the cache keys only on the focal text). */
        fun dropJavaBindingCaches()

        /** Remove open-document overlays at/under [root] (after a delete). */
        fun dropOverlaysUnder(root: Path)

        /** Re-point open-document overlays from [from] to [to] (after a move/rename). */
        fun rekeyOverlays(from: Path, to: Path)

        /** A path under an Android `res/` tree (a change to it can change the synthetic R). */
        fun isResourcePath(path: Path): Boolean

        /** A model/settings-level configuration change landed (dependencies, variant, SDK, …). The seam the
         *  out-of-process engines' snapshot push / hint fan-out hangs off. */
        fun configurationChanged()
    }

    /**
     * Monotonic stamp of the workspace CONFIGURATION (model generation ⊕ variant/settings/SDK changes):
     * everything that shapes classpaths and source roots but is not a source edit. The Analysis API
     * snapshot push keys off this, because the model's own `generation` does not advance on a variant or
     * SDK change even though the effective classpath does.
     */
    val configStamp = AtomicLong(0)

    /**
     * Reactions are DORMANT until [activate]: the hub subscribes at engine-construction time, but the
     * engine's own init performs model commits (`ensureKotlinStdlib`) while later-declared fields the
     * reactions touch are still uninitialized; a reaction throwing there would propagate OUT of the commit
     * and silently abort it (the bus rethrows a subscriber's first error). Pre-activation events are
     * dropped, matching pre-hub behavior (nothing listened during construction). Publishing is unaffected.
     */
    @Volatile
    private var active = false

    /** Arm the reactions; called as the LAST step of engine construction. */
    fun activate() {
        active = true
    }

    private val connection: MessageBusConnection = store.bus.connect().also { conn ->
        conn.subscribe(VfsTopics.CHANGES, object : VfsListener {
            override fun onEvents(events: List<VfsEvent>) = onVfs(events)
        })
        conn.subscribe(ProjectModelTopics.CHANGES, ProjectModelListener { events -> onModel(events) })
        conn.subscribe(SettingsTopics.CHANGES, SettingsListener { events -> onSettings(events) })
    }

    // ---- publish (the mutation sites call these; each is one synchronous bus fan-out) ----

    /** An existing file's content changed (an editor save / an appended resource entry). */
    fun fileChanged(path: Path, newText: String? = null) =
        publish(listOf(changedEvent(path, newText)))

    /** A refactoring's multi-file mutation: [edited] files' new contents were written, and the backing
     *  file possibly renamed ([moved]). Published as ONE batch so the reaction coalesces. */
    fun filesMutated(edited: List<Path>, moved: Pair<Path, Path>? = null) {
        val events = ArrayList<VfsEvent>(edited.size + 1)
        edited.mapTo(events) { changedEvent(it, null) }
        moved?.let { (from, to) -> events.add(FileMoved(store.vfs.fileFor(to), from.toString(), to.toString())) }
        publish(events)
    }

    fun fileCreated(path: Path) = publish(listOf(FileCreated(store.vfs.fileFor(path))))

    /** A batch of created files (a directory copy). One event per real file so remote consumers see the
     *  actual change set; the local reaction still invalidates once. */
    fun filesCreated(paths: List<Path>) =
        publish(paths.map { FileCreated(store.vfs.fileFor(it)) })

    fun fileDeleted(path: Path) = publish(listOf(FileDeleted(store.vfs.fileFor(path))))

    fun fileMoved(from: Path, to: Path) =
        publish(listOf(FileMoved(store.vfs.fileFor(to), from.toString(), to.toString())))

    /** Resolved libraries changed outside a model commit (a finished dependency resolution wrote
     *  `libraries.json`; an SDK install attached new sources). Same topic the model store publishes on,
     *  so consumers need one subscription for "the classpath world changed". */
    fun librariesChanged(project: ProjectId? = null) {
        store.bus.syncPublisher(ProjectModelTopics.CHANGES).onEvents(listOf(LibrariesChanged(project)))
    }

    /** A settings value changed (generic page control or the active build variant). */
    fun settingChanged(pageId: String, key: String, projectScoped: Boolean) {
        store.bus.syncPublisher(SettingsTopics.CHANGES)
            .onSettingsChanged(listOf(SettingChanged(pageId, key, projectScoped)))
    }

    private fun publish(events: List<VfsEvent>) {
        if (events.isEmpty()) return
        store.bus.syncPublisher(VfsTopics.CHANGES).onEvents(events)
    }

    private fun changedEvent(path: Path, newText: String?): FileChanged =
        FileChanged(store.vfs.fileFor(path), ContentHash(""), newText?.let(::hashOf) ?: ContentHash(""))

    private fun hashOf(text: String): ContentHash {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return ContentHash(digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }

    // ---- react (coalesced per batch; see the reaction table above) ----

    private fun onVfs(events: List<VfsEvent>) {
        if (!active) return
        var invalidate = false
        var synthetic = false
        var resync = false
        var changed = 0
        // The SET of source files changed (create/delete/move): part of the projected model the Analysis
        // API daemon derives from source roots, so it bumps the config stamp (a content edit does not).
        var membershipChanged = false
        for (e in events) {
            val p = Paths.get(e.file.path)
            when (e) {
                is FileChanged -> {
                    changed++
                    if (reactions.isResourcePath(p)) {
                        synthetic = true
                        if (p.toString().endsWith(".xml")) reactions.reindexSourceAsync(p)
                    } else if (isKotlin(p)) synthetic = true
                }

                is FileCreated -> if (!e.file.isDirectory) {
                    if (reactions.isResourcePath(p)) {
                        synthetic = true
                        if (p.toString().endsWith(".xml")) reactions.reindexSourceAsync(p)
                    } else if (isSource(p)) {
                        invalidate = true; membershipChanged = true
                        if (isKotlin(p)) synthetic = true
                    } else {
                        resync = true
                    }
                }

                is FileDeleted -> {
                    reactions.dropOverlaysUnder(p)
                    // Extension can't be trusted for a deleted directory (a package), so count any delete.
                    invalidate = true; synthetic = true; membershipChanged = true
                }

                is FileMoved -> {
                    reactions.rekeyOverlays(Paths.get(e.from), Paths.get(e.to))
                    invalidate = true; synthetic = true; membershipChanged = true
                }
            }
        }
        if (changed > 0) reactions.dropJavaBindingCaches()
        if (changed > 1) invalidate = true // a multi-file edit is a refactoring: declared names changed
        if (invalidate) {
            reactions.invalidateAnalyzers()
            synthetic = true; resync = true
        }
        if (synthetic) reactions.invalidateSyntheticClasses()
        if (resync) reactions.resyncIndex()
        if (membershipChanged) {
            configStamp.incrementAndGet()
            reactions.configurationChanged()
        }
    }

    private fun onModel(events: List<ProjectModelEvent>) {
        if (!active || events.isEmpty()) return
        configStamp.incrementAndGet()
        reactions.invalidateAnalyzers()
        reactions.invalidateSyntheticClasses()
        reactions.resyncIndex()
        reactions.configurationChanged()
    }

    private fun onSettings(events: List<SettingChanged>) {
        if (!active) return
        configStamp.incrementAndGet()
        if (events.any { it.key.startsWith(VARIANT_KEY_PREFIX) }) {
            reactions.invalidateAnalyzers()
            reactions.invalidateSyntheticClasses()
            reactions.resyncIndex()
        }
        reactions.configurationChanged()
    }

    private fun isKotlin(p: Path): Boolean =
        p.fileName?.toString()?.let { it.endsWith(".kt") || it.endsWith(".kts") } == true

    private fun isSource(p: Path): Boolean {
        val name = p.fileName?.toString() ?: return false
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts")
    }

    override fun close() {
        runCatching { connection.dispose() }
    }

    companion object {
        /** The engine-scoped pref-key prefix an active-variant change publishes under (page `build`). */
        const val VARIANT_KEY_PREFIX = "variant."
    }
}
