package dev.ide.ios

import dev.ide.android.layout.ANDROID_LAYOUT_MODEL
import dev.ide.android.layout.LAYOUT_RENDERER
import dev.ide.android.layout.remote.RemoteLayoutServices
import dev.ide.android.layouteditor.LayoutEditorPlugin
import dev.ide.android.layouteditor.LayoutEditorUiPlugin
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceLookup
import dev.ide.platform.log.Log
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiLogLevel
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.remote.RemoteBuild
import dev.ide.ui.backend.remote.RemoteEditor
import dev.ide.ui.platform.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.remote.RemoteFileTree
import dev.ide.ui.ext.UiPlugin
import dev.ide.ui.ext.asUiPlugin
import platform.Foundation.NSData

/**
 * The JVM the app embeds, as the app hands it to this framework.
 *
 * Most of the IDE's backend is JVM code. On iOS it runs in a JVM linked into the app, started by the app's own
 * native code (which is where JNI lives); this is the whole of what that code has to offer. `NSData` both ways,
 * because that is what it has on its side of the call and a picture is megabytes.
 */
interface EmbeddedJvm {
    /** Open the project at [projectRoot] in the embedded backend. Empty when it opened, else why it did not. */
    fun openProject(projectRoot: String): String

    /** One request of the layout services' byte protocol in, its answer out. Blocks until answered. */
    fun call(request: NSData): NSData

    /**
     * Whether the engine with the project open is compiled, not interpreted: the app carries it compiled
     * ahead of time when its build has one, and falls back to the JVM's, which only interprets. Interpreted,
     * it takes seconds to complete in a Kotlin file, and this host's own analysis answers Kotlin instead.
     */
    fun compiledEngine(): Boolean

    /**
     * Start the engine as a whole backend over [projectsRoot], the folder projects are kept in: every service
     * of `IdeBackend` is then answered by [call] (see `RemoteBackendWire`). Empty when it started, else why it
     * did not; a build with no engine compiled in has none to start. [session] is what the engine asks for the
     * signed-in user's access token (CodeAssist Free AI), the session being this app's.
     */
    fun startBackend(projectsRoot: String, session: EngineSession): String
}

/**
 * The app's store session, as the engine sees it.
 *
 * The agent runs in the engine and CodeAssist Free AI pays for its calls with the user's Supabase access token,
 * but the session is this app's: signed in through the system's sheet, its refresh token in the keychain. So
 * the engine keeps no session of its own and asks this instead, from whichever of its threads needs a token
 * (never the main thread's, since a token may take a network call to refresh). The engine does the wallet's
 * and the proxy's HTTP itself, with the project config it is given here when it starts.
 */
interface EngineSession {
    /** The Supabase project the store and the free tier use; blank when this build has none. */
    fun supabaseUrl(): String

    /** That project's publishable key. */
    fun supabaseKey(): String

    /** The free tier's primary proxy; blank for the Supabase `ai-proxy` function alone. */
    fun aiProxyUrl(): String

    /** Whether there is a session, live or stored. Never the network. */
    fun signedIn(): Boolean

    /** A current access token, refreshed first if spent; null when signed out. */
    fun accessToken(): String?

    /** A token to retry with after [stale] was refused (a 401); null when there is nothing left to try. */
    fun refreshAccessToken(stale: String?): String?
}

/**
 * What an embedded backend adds to this host: the layout editor, mounted as the plugin it is, with its two
 * services answered across the channel by the same code that answers them on a phone.
 */
internal class EmbeddedBackend(
    private val jvm: EmbeddedJvm,
    /** The project the app has open, which the embedded backend follows. */
    private val activeRoot: () -> String?,
) {
    @kotlin.concurrent.Volatile
    private var openedRoot: String? = null
    @kotlin.concurrent.Volatile
    private var openFailure: String? = null
    private val lock = dev.ide.ui.concurrent.UiLock()

    /**
     * The embedded backend opens a project the first time something is asked of it, not when the app does:
     * most projects opened on this host never show a layout, and starting an engine for them costs for nothing.
     */
    private fun ensureOpen() = lock.withLock {
        val root = activeRoot() ?: error("No project is open.")
        if (root != openedRoot) {
            openFailure = runCatching { jvm.openProject(root) }.getOrElse { it.message ?: it.toString() }.ifEmpty { null }
            openedRoot = root
        }
        openFailure?.let { error(it) }
    }

    /**
     * The project tree as the engine makes it (modules, source roots by role, packages in one row), or null
     * while the embedded backend has not opened [root] yet. Never waits for it to: a tree is asked for the
     * moment a project opens, and the engine takes seconds to start, so the first ask starts it on [starter]
     * and [onReady] says when to ask again.
     */
    fun fileTree(root: String, mode: TreeViewMode, starter: (work: () -> Unit) -> Unit, onReady: () -> Unit): TreeNode? {
        if (openedRoot == root) {
            if (openFailure != null) return null
            return runCatching { RemoteFileTree.read(jvm.call(RemoteFileTree.request(mode).toNSData()).toByteArray()) }
                .onFailure { Log.logger("ide.embedded").warn("no project tree from the embedded backend: ${it.message}") }
                .getOrNull()
        }
        if (starting != root) {
            starting = root
            starter {
                runCatching { ensureOpen() }
                if (openedRoot == root && openFailure == null) onReady()
            }
        }
        return null
    }

    // ---- the editor's language services ------------------------------------------------------------------

    private val editor = RemoteEditor.Client { request -> jvm.call(request.toNSData()).toByteArray() }
    private val editorLog = Log.logger("ide.embedded")

    /**
     * Whether the engine can answer for [path]: it has the app's project open, and the file is in it. Asking is
     * never what opens a project in the engine (the tree does that, as the project opens), so until it has,
     * and for a file outside the project, the host's own analysis answers.
     */
    private fun engineHas(path: String): Boolean {
        val root = openedRoot ?: return false
        return openFailure == null && root == activeRoot() && path.startsWith("$root/")
    }

    /** [ask]'s answer and how long it took, or null when the engine cannot answer or failed to. */
    private fun <T> asked(what: String, path: String, ask: () -> T): T? {
        if (!engineHas(path)) return null
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        return try {
            ask().also { editorLog.info("engine $what ${path.substringAfterLast('/')}: ${started.elapsedNow().inWholeMilliseconds} ms") }
        } catch (preempted: dev.ide.ui.backend.AnalysisPreempted) {
            throw preempted
        } catch (t: Throwable) {
            editorLog.warn("engine $what failed after ${started.elapsedNow().inWholeMilliseconds} ms: ${t.message}")
            null
        }
    }

    /** Whether the engine answers fast enough to be asked on every keystroke in any language; see [EmbeddedJvm.compiledEngine]. */
    val answersKotlin: Boolean get() = openedRoot != null && openFailure == null && runCatching { jvm.compiledEngine() }.getOrDefault(false)

    /** Completion from the engine, or null for the host's own to answer. Blocks until answered. */
    fun complete(path: String, text: String, offset: Int): UiCompletionResult? =
        asked("completion", path) { editor.complete(path, text, offset) }

    fun completionAccepted(path: String, label: String) { asked("accepted", path) { editor.completionAccepted(path, label) } }

    /** Diagnostics from the engine, or null for the host's own. May throw [dev.ide.ui.backend.AnalysisPreempted]. */
    fun analyze(path: String, text: String): List<UiDiagnostic>? = asked("analysis", path) { editor.analyze(path, text) }

    /** Tells the engine what [path] now holds: it does not watch the folder, and this host writes the files. */
    fun documentChanged(path: String, text: String) { asked("document", path) { editor.updateDocument(path, text) } }

    /** The root an open was last started for, so a tree asked for again meanwhile does not start another. */
    @kotlin.concurrent.Volatile
    private var starting: String? = null

    /**
     * Build and run, done by the embedded backend. The console's state is asked for while a build runs (see
     * [RemoteBuild]); between builds nothing is asked.
     */
    val build: BuildService = object : BuildService {
        private val state = MutableStateFlow(BuildState())
        private val client = RemoteBuild.Client { request -> ensureOpen(); jvm.call(request.toNSData()).toByteArray() }
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher.limitedParallelism(1))
        private var watching: Job? = null

        override val buildState: StateFlow<BuildState> = state
        override fun supported(): Boolean = true

        /** Empty until the engine has the project open: the Run picker is drawn from the main thread. */
        override fun runTasks(): List<RunTaskOption> =
            if (openedRoot == null || openedRoot != activeRoot()) emptyList()
            else runCatching { client.tasks() }.getOrDefault(emptyList())

        override fun runTask(id: String) = start { client.runTask(id) }
        override fun runBuild() = start { client.runBuild() }
        override fun stopBuild() { scope.launch { runCatching { client.stop() } } }

        private fun start(ask: () -> Unit) {
            state.value = BuildState(status = RunStatus.Running)
            watching?.cancel()
            watching = scope.launch {
                try {
                    ask()
                } catch (t: Throwable) {
                    state.value = BuildState(
                        status = RunStatus.Failed,
                        log = listOf(BuildLogLine(t.message ?: t.toString(), UiLogLevel.Error)),
                    )
                    return@launch
                }
                // The engine starts a build on a thread of its own, so the first answers may still say idle.
                var sawRunning = false
                var asked = 0
                while (isActive) {
                    val now = runCatching { client.state() }.getOrNull()
                    if (now != null) {
                        val running = now.status == RunStatus.Running
                        sawRunning = sawRunning || running
                        if (sawRunning || now.status != RunStatus.Idle) state.value = now
                        if (sawRunning && !running || !sawRunning && now.status != RunStatus.Idle && asked > 4) break
                    }
                    if (!sawRunning && ++asked > 60) { state.value = now ?: BuildState(status = RunStatus.Failed); break }
                    delay(250)
                }
            }
        }
    }

    private val remote = RemoteLayoutServices { request ->
        ensureOpen()
        jvm.call(request.toNSData()).toByteArray()
    }

    private val services = object : ServiceLookup {
        override fun <T : Any> getService(key: ServiceKey<T>): T =
            getServiceOrNull(key) ?: error("no service ${key.id} on this host")

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> getServiceOrNull(key: ServiceKey<T>): T? = when (key.id) {
            LAYOUT_RENDERER.id -> remote.renderer as T
            ANDROID_LAYOUT_MODEL.id -> remote.model as T
            else -> null
        }
    }

    val uiPlugins: List<UiPlugin> = listOf(LayoutEditorUiPlugin.asUiPlugin(LayoutEditorPlugin.ID, services))
}
