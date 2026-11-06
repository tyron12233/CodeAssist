package dev.ide.core.lsp

import dev.ide.core.ApplicationEnvironment
import dev.ide.lang.LANGUAGE_BACKEND_EP
import dev.ide.lsp.LANGUAGE_SERVER_EP
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.lsp.client.LspLanguageSupport
import dev.ide.lsp.client.LspSessionManager
import dev.ide.platform.log.Log
import dev.ide.platform.log.Logger
import dev.ide.platform.notify.error
import dev.ide.platform.notify.warn
import dev.ide.vfs.FileChanged
import dev.ide.vfs.FileCreated
import dev.ide.vfs.FileDeleted
import dev.ide.vfs.FileMoved
import dev.ide.vfs.VfsEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the plugins' language servers ([LANGUAGE_SERVER_EP]) meet the IDE. Owned by [ApplicationEnvironment]:
 * once every plugin has loaded, [registerAll] turns each definition into the standard extension points (a
 * language backend, diagnostics, completion, code actions, navigation, usages, rename), registered under the
 * plugin that contributed it. The engine then serves those languages with no idea a server is involved.
 *
 * Sessions are per (definition, project root) and shut down with their project ([projectClosed]). The engine
 * tells the bridge what the servers need to hear about: a closed tab, a saved or changed file.
 */
class LanguageServerBridge internal constructor(private val env: ApplicationEnvironment) : AutoCloseable {

    private val loggers = ConcurrentHashMap<String, Logger>()
    private val log = Log.logger("lsp")

    private val diagnosticsFlow = MutableSharedFlow<Set<Path>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Files whose diagnostics a server changed outside an analysis call; the editor re-analyzes the open ones. */
    val diagnosticsChanged: SharedFlow<Set<Path>> = diagnosticsFlow.asSharedFlow()

    private val editsFlow = MutableSharedFlow<Map<Path, List<dev.ide.lang.incremental.DocumentEdit>>>(extraBufferCapacity = 16)

    /**
     * Edits a server asked for on its own (`workspace/applyEdit` outside a command the IDE ran: a refactoring
     * that finished in the background, a fix-all on save). The editor applies them the way it applies a
     * multi-file quick fix, so an open file is edited through its tab and stays undoable.
     */
    val externalEdits: SharedFlow<Map<Path, List<dev.ide.lang.incremental.DocumentEdit>>> = editsFlow.asSharedFlow()

    val sessions: LspSessionManager = LspSessionManager(object : LspSessionManager.Host {
        override fun projectRootFor(file: Path): Path? {
            val root = env.activeEngine?.workspaceRoot?.toAbsolutePath()?.normalize() ?: return null
            return root.takeIf { file.toAbsolutePath().normalize().startsWith(it) }
        }

        override fun diagnosticsChanged(paths: Set<Path>) {
            if (paths.isNotEmpty()) diagnosticsFlow.tryEmit(paths)
        }

        override fun applyEdit(session: dev.ide.lsp.client.LspSession, edit: kotlinx.serialization.json.JsonObject): Boolean {
            val edits = runCatching { session.documentEdits(edit) }.getOrNull()?.filterValues { it.isNotEmpty() }.orEmpty()
            // Applied only when an editor is listening for it; with none, the server is told so and can retry.
            return edits.isNotEmpty() && editsFlow.subscriptionCount.value > 0 && editsFlow.tryEmit(edits)
        }

        override fun message(definition: LanguageServerDefinition, type: Int, text: String) {
            when (type) {
                1 -> env.userMessages.error(text)
                2 -> env.userMessages.warn(text)
                else -> loggerFor(definition).info(text)
            }
        }

        override fun loggerFor(definition: LanguageServerDefinition): Logger =
            loggers[definition.id] ?: Log.logger("lsp:${definition.id}")

        override val clientVersion: String get() = env.hostVersion ?: "dev"
    })

    private val supports = ArrayList<LspLanguageSupport>()

    /** The registered support serving [language], or null when no language server does. */
    fun supportFor(language: dev.ide.lang.LanguageId): LspLanguageSupport? =
        synchronized(this) { supports.firstOrNull { language in it.definition.languages } }

    /** Whether any plugin registered a language server: the engine skips the bookkeeping otherwise. */
    val hasServers: Boolean get() = supports.isNotEmpty()

    /** The adapters registered for each definition, by identity, so a removed definition can be taken back. */
    private val registered = java.util.IdentityHashMap<LanguageServerDefinition, List<dev.ide.platform.Disposable>>()

    /**
     * Bring the adapters in line with the [LANGUAGE_SERVER_EP] contributions: register the ones not registered
     * yet, and take back (adapters disposed, sessions shut down) the ones no longer contributed. Called after
     * the plugin load, and again whenever the user's own servers change. A project that is already open keeps
     * the languages it opened with; one opened afterwards sees the change.
     */
    @Synchronized
    internal fun registerAll() {
        val registry = env.platform.extensions
        val contributions = registry.contributions(LANGUAGE_SERVER_EP)
        val current = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<LanguageServerDefinition, Boolean>())
        contributions.forEach { current += it.impl }
        for (gone in registered.keys.filter { it !in current }) {
            registered.remove(gone)?.forEach { runCatching { it.dispose() } }
            supports.removeAll { it.definition === gone }
            sessions.closeDefinition(gone.id)
            log.info("language server '${gone.id}' removed")
        }
        // Languages some OTHER backend serves: our own adapters' backends do not count against a definition.
        val claimed = registry.extensions(LANGUAGE_BACKEND_EP)
            .filter { b -> supports.none { it.backend === b } }
            .flatMapTo(HashSet()) { it.languages }
        for (contribution in contributions) {
            val definition = contribution.impl
            if (definition in registered) continue
            if (registered.keys.any { it.id == definition.id }) {
                log.warn("language server '${definition.id}' from '${contribution.plugin.value}' is already registered; skipped")
                continue
            }
            if (definition.languages.isEmpty()) {
                log.warn("language server '${definition.id}' serves no language; skipped")
                continue
            }
            loggers[definition.id] = Log.logger("lsp:${definition.id}", contribution.plugin.value)
            val support = LspLanguageSupport(definition, sessions)
            // A plugin that also registers a backend of its own for these languages keeps it: the server then
            // answers only what the engine asks the providers for.
            val withBackend = definition.languages.none { it in claimed }
            registered[definition] = support.register(registry, contribution.plugin, withBackend)
            supports += support
            log.info("language server '${definition.id}' serves ${definition.languages.joinToString { it.id }}" +
                if (withBackend) "" else " (alongside the plugin's own backend)")
        }
    }

    /** How [definitionId]'s servers are doing, for Settings: running, starting, failed (with why), or not started. */
    fun status(definitionId: String): String {
        val mine = sessions.sessions().filter { it.definition.id == definitionId }
        if (mine.isEmpty()) return "Not started"
        mine.firstOrNull { it.state == dev.ide.lsp.client.LspSession.State.READY }?.let { return "Running" }
        mine.firstOrNull { it.state == dev.ide.lsp.client.LspSession.State.STARTING || it.state == dev.ide.lsp.client.LspSession.State.NEW }
            ?.let { return "Starting" }
        val failed = mine.firstOrNull { it.failureReason != null }
        return failed?.failureReason?.let { "Stopped: $it" } ?: "Stopped"
    }

    /** Shut down [definitionId]'s sessions, so the next request starts it with its current settings. */
    fun restart(definitionId: String) = sessions.closeDefinition(definitionId)

    /** The project at [root] closed: shut its servers down. */
    fun projectClosed(root: Path) {
        if (hasServers) sessions.closeProject(root)
    }

    /** The editor closed [file]'s tab. */
    fun documentClosed(file: Path) {
        if (hasServers) sessions.documentClosed(file)
    }

    /** Files changed on disk (a save, a create, a delete, a move): the servers' watchers hear about them. */
    fun filesChanged(events: List<VfsEvent>) {
        if (!hasServers || events.isEmpty()) return
        val changes = ArrayList<Pair<Path, Int>>(events.size)
        for (e in events) {
            when (e) {
                is FileCreated -> path(e.file.path)?.let { changes += it to CREATED }
                is FileChanged -> path(e.file.path)?.let { changes += it to CHANGED; sessions.documentSaved(it) }
                is FileDeleted -> path(e.file.path)?.let { changes += it to DELETED }
                is FileMoved -> {
                    path(e.from)?.let { changes += it to DELETED }
                    path(e.to)?.let { changes += it to CREATED }
                }
            }
        }
        sessions.filesChanged(changes)
    }

    private fun path(p: String): Path? = runCatching { Paths.get(p).toAbsolutePath().normalize() }.getOrNull()

    override fun close() {
        runCatching { sessions.close() }
    }

    private companion object {
        const val CREATED = 1
        const val CHANGED = 2
        const val DELETED = 3
    }
}
