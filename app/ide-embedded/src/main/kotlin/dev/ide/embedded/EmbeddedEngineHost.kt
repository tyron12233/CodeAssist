package dev.ide.embedded

import dev.ide.core.IdeServices
import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.ui.backend.remote.RemoteBackendServer
import dev.ide.ui.backend.remote.RemoteBackendWire
import dev.ide.ui.backend.remote.RemoteBuild
import dev.ide.ui.backend.remote.RemoteEditor
import dev.ide.ui.backend.remote.RemoteFileTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

/**
 * The engine as an iOS app runs it compiled ahead of time.
 *
 * The JVM an iOS app can embed only interprets, and an engine interpreted answers a completion in seconds.
 * So the engine itself (the project model, the indexes, the language services, the build with its compilers)
 * is compiled into a native image the app carries, and this is what the app enters it through: the same two
 * calls [EmbeddedLayoutHost] offers, speaking the same byte protocols.
 *
 * What it does not have is what needs a class loaded at run time, which such an image cannot do: layoutlib
 * and the project's own views. Those stay in the app's JVM behind [EmbeddedLayoutHost], which the app starts
 * only when a layout is opened.
 */
object EmbeddedEngineHost {
    private var engine: IdeServices? = null
    private var openRoot: String? = null
    @Volatile private var backend: IdeServicesBackend? = null
    @Volatile private var buildServer: RemoteBuild.Server? = null
    @Volatile private var editorServer: RemoteEditor.Server? = null

    /** aapt2 as the app has it linked in: its `main` with [run]'s arguments, what it prints sent to a file. */
    @JvmStatic
    fun installAapt2(run: (args: Array<String>, output: String) -> Int) = NativeAapt2.install(run)

    /**
     * The app's store session, asked by question (see [AppSession]): installed by the app before [start], so
     * the agent the backend makes finds CodeAssist Free AI registered.
     */
    @JvmStatic
    fun installSession(ask: (question: Int, arg: String?) -> String?) {
        AppSession.ask = ask
    }

    /** The whole backend, when the app asked for one with [start]: the far side of `remoteIdeBackend`. */
    @Volatile private var whole: RemoteBackendServer? = null
    private var wholeScope: CoroutineScope? = null

    /**
     * Start the backend the way the desktop and Android apps do: a project manager over [projectsRoot], no
     * project open, and every service of `IdeBackend` answered across the channel ([RemoteBackendServer]).
     * The app then creates, opens and switches projects through the backend itself, and [open] has nothing
     * left to do. Empty, or why it could not start. Once per process; a second call answers as the first did.
     */
    @JvmStatic
    @Synchronized
    fun start(projectsRoot: String): String = try {
        if (whole == null) {
            close()
            val manager = ProjectManager.desktop(Paths.get(projectsRoot))
            // Before the backend: its agent looks for the credits service once, as it is made.
            val freeAi = registerFreeAi(manager)
            val host = IdeServicesBackend(initial = null, manager = manager)
            reportFreeAi(freeAi, host)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            backend = host
            buildServer = RemoteBuild.Server(host.build)
            editorServer = RemoteEditor.Server(host.editor)
            wholeScope = scope
            whole = RemoteBackendServer(host, scope)
        }
        ""
    } catch (t: Throwable) {
        t.printStackTrace()
        "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
    }

    /** Open the project at [projectRoot], closing the one open before. Empty, or why it could not be opened. */
    @JvmStatic
    @Synchronized
    fun open(projectRoot: String): String = try {
        // A whole backend opens its projects itself, asked to across the channel.
        if (whole != null) return ""
        if (projectRoot != openRoot) {
            close()
            val services = IdeServices.open(Paths.get(projectRoot))
            val host = IdeServicesBackend(services)
            engine = services
            backend = host
            buildServer = RemoteBuild.Server(host.build)
            editorServer = RemoteEditor.Server(host.editor)
            openRoot = projectRoot
        }
        ""
    } catch (t: Throwable) {
        t.printStackTrace()
        "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
    }

    /**
     * Answer one request of the build's, the project tree's or the editor's protocol. Never throws: with no
     * project open, or on any failure, the answer says so. Safe from several threads at once.
     */
    @JvmStatic
    fun call(request: ByteArray): ByteArray = try {
        when {
            RemoteBackendWire.handles(request) -> whole?.handle(request) ?: RemoteBackendWire.failure(NO_PROJECT)
            RemoteEditor.handles(request) -> editorServer?.let { server -> runBlocking { server.handle(request) } }
                ?: RemoteEditor.failure(NO_PROJECT)
            RemoteBuild.handles(request) -> buildServer?.handle(request) ?: RemoteBuild.failure(NO_PROJECT)
            else -> when (val mode = RemoteFileTree.modeOf(request)) {
                null -> ByteArray(0)
                else -> backend?.let { RemoteFileTree.answer(it.files.fileTree(mode)) } ?: RemoteFileTree.failure(NO_PROJECT)
            }
        }
    } catch (t: Throwable) {
        t.printStackTrace()
        // Whichever protocol it was, its near side reads an answer it cannot make sense of as a failure.
        ByteArray(0)
    }

    @JvmStatic
    @Synchronized
    fun close() {
        // A whole backend lives as long as the process: the app that started it never closes it.
        if (whole != null) return
        backend = null
        buildServer = null
        editorServer = null
        runCatching { engine?.close() }
        engine = null
        openRoot = null
    }

    private const val NO_PROJECT = "No project is open in the embedded backend."

    /**
     * CodeAssist Free AI, as the Android app registers it, but over the app's session ([AppSession]) rather
     * than one of the engine's own: the app passes the Supabase project as properties, and its session through
     * [installSession]. False when either is missing, and the agent then offers only bring-your-own-key.
     */
    private fun registerFreeAi(manager: ProjectManager): Boolean {
        val url = System.getProperty(SUPABASE_URL).orEmpty()
        val key = System.getProperty(SUPABASE_KEY).orEmpty()
        if (url.isBlank() || key.isBlank() || AppSession.ask == null) return false
        val proxy = System.getProperty(AI_PROXY_URL).orEmpty()
        manager.applicationContainer.registerServiceIfAbsent(dev.ide.core.AI_CREDITS_SERVICE) {
            dev.ide.store.impl.SupabaseAiCreditsService(url, key, AppSession, proxyUrl = proxy)
        }
        return true
    }

    /** One line in the app's log: whether Free AI is wired, whether there is a session, which providers the agent offers. */
    private fun reportFreeAi(registered: Boolean, host: IdeServicesBackend) {
        val line = runCatching {
            val config = host.agent.config()
            "free AI: credits service ${if (registered) "registered" else "NOT registered"}, " +
                "session ${if (registered && AppSession.hasStoredSession()) "present" else "absent"}, " +
                "providers ${config.providers.map { it.id }}, selected ${config.selectedProvider.ifEmpty { "(none)" }}"
        }.getOrElse { "free AI: could not report (${it.javaClass.simpleName}: ${it.message})" }
        System.err.println(line)
    }

    private const val SUPABASE_URL = "codeassist.supabase.url"
    private const val SUPABASE_KEY = "codeassist.supabase.key"
    private const val AI_PROXY_URL = "codeassist.ai.proxy.url"
}

/**
 * The store session of the app the engine runs in. The app signs in, keeps the refresh token in its keychain
 * and refreshes the access token; the engine only asks, from whichever thread needs a token. Two owners of one
 * refresh token would each spend it, and refresh tokens rotate, so the second would sign the user out.
 */
internal object AppSession : dev.ide.store.impl.AiCreditsSession {
    /** The app's answer to a question: 0 is there a session ("1" or null), 1 a token, 2 a token after a refusal. */
    @Volatile
    var ask: ((question: Int, arg: String?) -> String?)? = null

    private fun asked(question: Int, arg: String? = null): String? =
        runCatching { ask?.invoke(question, arg) }.getOrNull()

    override fun authAvailable(): Boolean = ask != null
    override fun hasStoredSession(): Boolean = asked(0) != null
    override fun bearer(): String? = asked(1)
    override fun reauthorize(stale: String?): String? = asked(2, stale)
}
