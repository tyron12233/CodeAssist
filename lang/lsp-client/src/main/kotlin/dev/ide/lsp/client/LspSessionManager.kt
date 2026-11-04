package dev.ide.lsp.client

import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.platform.log.Log
import dev.ide.platform.log.Logger
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Every running language server, keyed by (definition, workspace root). Application-scoped: a session belongs
 * to one project's root, and [closeProject] shuts down that project's sessions when it closes.
 *
 * A session is started on a background thread the first time anything asks for it, so the engine thread that
 * asked never waits on a process launch: it gets the session at once and each request waits only as long as
 * its own budget allows for the handshake. A server that exits is restarted on the next request; only one that
 * fails [MAX_RESTARTS] times within [RESTART_WINDOW_MS] is left down (a crash loop). One killed after running
 * fine, which Android 12+ does to an app's child processes in the background, comes back every time. One that
 * is unavailable (no toolchain yet) is asked again after [UNAVAILABLE_RETRY_MS].
 */
class LspSessionManager(
    private val host: Host,
    /** Wall-clock milliseconds, for the restart window; a test moves it. */
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    /** What the manager needs from the IDE. */
    interface Host {
        /** The root of the open project [file] belongs to, or null when it is in none. */
        fun projectRootFor(file: Path): Path?

        /** Diagnostics for [paths] changed outside an analysis call: the editor should ask again. */
        fun diagnosticsChanged(paths: Set<Path>) {}

        /** The server asked to apply [edit] (an LSP `WorkspaceEdit`) on its own. Return whether it was applied. */
        fun applyEdit(session: LspSession, edit: JsonObject): Boolean = false

        /** Something the user should see: a server's `showMessage`, or that a server stopped for good. */
        fun message(definition: LanguageServerDefinition, type: Int, text: String) {}

        /** The logger a definition's server reports to (attributed to its plugin). */
        fun loggerFor(definition: LanguageServerDefinition): Logger = Log.logger("lsp:${definition.id}")

        /** The IDE's version, for `clientInfo`. */
        val clientVersion: String get() = "dev"
    }

    private data class Key(val definition: String, val root: Path)

    private class Slot(
        @Volatile var session: LspSession,
        @Volatile var unavailableSince: Long = 0,
        @Volatile var givenUp: Boolean = false,
    ) {
        /** When this slot's sessions failed, newest last; only those within [RESTART_WINDOW_MS] count. */
        val failures = ArrayDeque<Long>()
    }

    private val slots = ConcurrentHashMap<Key, Slot>()
    private val starter: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "lsp-start").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    /**
     * The session serving [file] for [definition], starting (or restarting) it if needed. Never blocks on the
     * server: the session may still be starting, and its requests wait for the handshake within their own
     * budget. Null when [file] is in no open project or the server is down for the session.
     */
    fun sessionFor(definition: LanguageServerDefinition, file: Path): LspSession? {
        if (closed) return null
        val projectRoot = host.projectRootFor(file) ?: run {
            if (dev.ide.platform.log.PerfTrace.enabled) {
                host.loggerFor(definition).info("lsp-perf no session for $file: it is in no open project")
            }
            return null
        }
        // A tab left open on a file whose folder was deleted or moved: its relative imports resolve nowhere, and
        // `rootFor` finds no package marker above it, so it would start a server of its own at the project root
        // (a second Dart analysis server, ~100 MB) for a buffer nothing else can see.
        if (file.parent?.let { Files.isDirectory(it) } == false) {
            if (dev.ide.platform.log.PerfTrace.enabled) {
                host.loggerFor(definition).info("lsp-perf no session for $file: its directory no longer exists")
            }
            return null
        }
        val root = rootFor(definition, file, projectRoot)
        val key = Key(definition.id, root)
        val slot = slots.computeIfAbsent(key) { Slot(newSession(definition, root, projectRoot)) }
        synchronized(slot) {
            val s = slot.session
            when (s.state) {
                LspSession.State.NEW -> launch(s)
                LspSession.State.UNAVAILABLE -> {
                    val now = clock()
                    if (slot.unavailableSince == 0L) slot.unavailableSince = now
                    if (now - slot.unavailableSince < UNAVAILABLE_RETRY_MS) return null
                    slot.unavailableSince = 0
                    slot.session = newSession(definition, root, projectRoot).also(::launch)
                }
                LspSession.State.FAILED -> {
                    if (slot.givenUp) return null
                    val now = clock()
                    slot.failures.addLast(now)
                    while (slot.failures.isNotEmpty() && now - slot.failures.first() > RESTART_WINDOW_MS) slot.failures.removeFirst()
                    if (slot.failures.size > MAX_RESTARTS) {
                        slot.givenUp = true
                        host.message(
                            definition, 1,
                            "${definition.displayName} stopped (${s.failureReason ?: "it exited"}) and keeps failing, so it will not be restarted until the project is reopened.",
                        )
                        return null
                    }
                    s.context.logger.info("restarting ${definition.id} (${s.failureReason ?: "it exited"}; ${slot.failures.size} failure(s) in the last ${RESTART_WINDOW_MS / 60_000} min)")
                    slot.session = newSession(definition, root, projectRoot).also(::launch)
                }
                LspSession.State.CLOSED -> slot.session = newSession(definition, root, projectRoot).also(::launch)
                else -> Unit
            }
            return slot.session
        }
    }

    /** The running session for [file], without starting one. */
    fun existingSession(definition: LanguageServerDefinition, file: Path): LspSession? {
        val projectRoot = host.projectRootFor(file) ?: return null
        return slots[Key(definition.id, rootFor(definition, file, projectRoot))]?.session
    }

    /** Every session whose workspace contains [file]. */
    fun sessionsContaining(file: Path): List<LspSession> {
        val p = file.toAbsolutePath().normalize()
        return slots.values.map { it.session }.filter { p.startsWith(it.context.root) || p.startsWith(it.context.projectRoot) }
    }

    fun sessions(): List<LspSession> = slots.values.map { it.session }

    /** The editor closed [file]'s tab. */
    fun documentClosed(file: Path) = sessionsContaining(file).forEach { it.close(file) }

    /** [file] was saved. */
    fun documentSaved(file: Path) = sessionsContaining(file).forEach { it.saved(file) }

    /** Files changed on disk: (path, 1 created / 2 changed / 3 deleted). */
    fun filesChanged(changes: List<Pair<Path, Int>>) {
        if (changes.isEmpty()) return
        for (session in sessions()) {
            val mine = changes.filter { (p, _) -> p.startsWith(session.context.root) }
            if (mine.isNotEmpty()) session.filesChanged(mine)
        }
    }

    /**
     * Shut down every session of [definitionId]: the definition was removed, or its command changed. The next
     * request for one of its files starts a fresh session (with a fresh restart budget) if it still exists.
     */
    fun closeDefinition(definitionId: String) {
        val gone = slots.entries.filter { it.key.definition == definitionId }
        gone.forEach { slots.remove(it.key) }
        gone.forEach { e -> starter.execute { e.value.session.shutdown() } }
    }

    /** Shut down every session started for [projectRoot]. */
    fun closeProject(projectRoot: Path) {
        val root = projectRoot.toAbsolutePath().normalize()
        val gone = slots.entries.filter { it.value.session.context.projectRoot == root }
        gone.forEach { slots.remove(it.key) }
        gone.forEach { e -> starter.execute { e.value.session.shutdown() } }
    }

    override fun close() {
        closed = true
        val all = slots.values.map { it.session }
        slots.clear()
        all.forEach { runCatching { it.shutdown() } }
        starter.shutdown()
    }

    private fun rootFor(definition: LanguageServerDefinition, file: Path, projectRoot: Path): Path {
        val probe = Context(projectRoot, projectRoot, host.loggerFor(definition))
        val custom = runCatching { definition.rootFor(file, probe) }.getOrNull()
        return (custom ?: projectRoot).toAbsolutePath().normalize()
    }

    private fun newSession(definition: LanguageServerDefinition, root: Path, projectRoot: Path): LspSession =
        LspSession(
            definition,
            Context(root, projectRoot.toAbsolutePath().normalize(), host.loggerFor(definition)),
            events,
            host.clientVersion,
        )

    private fun launch(session: LspSession) {
        if (closed) return
        starter.execute { session.start() }
    }

    private val events = object : LspSession.Events {
        override fun diagnosticsChanged(session: LspSession, path: Path) = host.diagnosticsChanged(setOf(path))

        override fun applyEdit(session: LspSession, edit: JsonObject): Boolean = host.applyEdit(session, edit)

        override fun message(session: LspSession, type: Int, text: String) =
            host.message(session.definition, type, "${session.definition.displayName}: $text")

        override fun refreshRequested(session: LspSession) =
            host.diagnosticsChanged(session.openDocuments().mapTo(HashSet()) { it.path })

        override fun ready(session: LspSession, waited: Set<Path>) = host.diagnosticsChanged(waited)

        override fun exited(session: LspSession, failure: Throwable?) {
            // Restarted lazily by the next sessionFor; the open documents are re-sent then, so every open
            // editor's diagnostics are asked for again now.
            host.diagnosticsChanged(session.openDocuments().mapTo(HashSet()) { it.path })
        }
    }

    private class Context(
        override val root: Path,
        override val projectRoot: Path,
        override val logger: Logger,
    ) : LanguageServerContext

    companion object {
        /** Failures within [RESTART_WINDOW_MS] that mean a crash loop rather than a server that was killed. */
        const val MAX_RESTARTS = 3
        const val RESTART_WINDOW_MS = 3 * 60_000L
        const val UNAVAILABLE_RETRY_MS = 30_000L
    }
}
