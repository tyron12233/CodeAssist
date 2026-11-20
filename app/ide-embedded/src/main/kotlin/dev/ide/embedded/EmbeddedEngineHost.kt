package dev.ide.embedded

import dev.ide.core.IdeServices
import dev.ide.core.IdeServicesBackend
import dev.ide.ui.backend.remote.RemoteBuild
import dev.ide.ui.backend.remote.RemoteEditor
import dev.ide.ui.backend.remote.RemoteFileTree
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

    /** Open the project at [projectRoot], closing the one open before. Empty, or why it could not be opened. */
    @JvmStatic
    @Synchronized
    fun open(projectRoot: String): String = try {
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
        backend = null
        buildServer = null
        editorServer = null
        runCatching { engine?.close() }
        engine = null
        openRoot = null
    }

    private const val NO_PROJECT = "No project is open in the embedded backend."
}
