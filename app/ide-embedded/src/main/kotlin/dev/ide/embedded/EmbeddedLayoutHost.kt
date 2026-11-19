package dev.ide.embedded

import dev.ide.android.layout.ANDROID_LAYOUT_MODEL
import dev.ide.android.layout.LAYOUT_RENDERER
import dev.ide.android.layout.remote.LayoutServiceDispatcher
import dev.ide.core.IdeServices
import dev.ide.core.IdeServicesBackend
import dev.ide.preview.layoutlib.LayoutlibRealViewRuntime
import dev.ide.ui.backend.remote.RemoteBuild
import dev.ide.ui.backend.remote.RemoteEditor
import dev.ide.ui.backend.remote.RemoteFileTree
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Paths

/**
 * The layout services of the real backend, for a caller on the other side of JNI.
 *
 * [open] starts the engine on a project the way the desktop app does and registers layoutlib as its real-view
 * renderer; [call] takes one request of the layout services' byte protocol and answers it from the engine's
 * own `LayoutRenderer` and `AndroidLayoutModel`, the objects the layout editor is handed on a phone. Static,
 * and typed by strings and byte arrays only, because that is what is easy to call through JNI.
 */
object EmbeddedLayoutHost {
    private var openRoot: String? = null
    private var engine: IdeServices? = null
    private var backend: IdeServicesBackend? = null
    private var dispatcher: LayoutServiceDispatcher? = null
    private var runtime: LayoutlibRealViewRuntime? = null
    private var buildServer: RemoteBuild.Server? = null
    @Volatile
    private var editorServer: RemoteEditor.Server? = null

    /**
     * Open the project at [projectRoot], closing the one open before. Returns an empty string, or why it could
     * not be opened. layoutlib starts once per process, so [layoutlibHome] is honoured on the first open only.
     * Its native libraries are looked for in the `layoutlib.native` system property's folder when that is set
     * (an iOS app can only load them from its Frameworks folder), else in the home's own `native`.
     */
    @JvmStatic
    @Synchronized
    fun open(projectRoot: String, layoutlibHome: String, cacheDir: String): String = try {
        if (projectRoot != openRoot) {
            close()
            NativeAapt2.install()
            NativeD8.install()
            NativeKotlinc.install()
            NativeDigests.install()
            NativeDigest.install()
            val nativeDir = System.getProperty("layoutlib.native")?.let(::File) ?: File(layoutlibHome, "native")
            val renderer = runtime ?: LayoutlibRealViewRuntime(File(layoutlibHome), File(cacheDir), nativeDir).also { runtime = it }
            val services = IdeServices.open(Paths.get(projectRoot), realViewRuntime = renderer)
            val host = IdeServicesBackend(services)
            val container = services.store.workspaceContainer
            dispatcher = LayoutServiceDispatcher(container.getService(LAYOUT_RENDERER), container.getService(ANDROID_LAYOUT_MODEL))
            engine = services
            backend = host
            buildServer = RemoteBuild.Server(host.build)
            editorServer = RemoteEditor.Server(host.editor)
            openRoot = projectRoot
            runRequestedBuild(File(projectRoot, ".platform/embedded-build"), host, services)
        }
        ""
    } catch (t: Throwable) {
        // The trace goes to the process's error stream, which the iOS app keeps in a file: the one line
        // returned is all the editor has room to show.
        t.printStackTrace()
        "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
    }

    /** Answer one request. Never throws: with no project open, or on any failure, the answer says so. */
    @JvmStatic
    fun call(request: ByteArray): ByteArray {
        if (RemoteEditor.handles(request)) {
            // Not under this object's lock: the app gives editor calls a thread of their own, so a completion
            // is answered while a render or a build's state is being asked for on the other.
            val server = editorServer ?: return RemoteEditor.failure("No project is open in the embedded backend.")
            val started = System.nanoTime()
            val reply = runBlocking { server.handle(request) }
            val millis = (System.nanoTime() - started) / 1_000_000
            // Until these are known to be fast enough in a JVM that only interprets, each one says how long it took.
            if (millis >= 20) System.err.println("editor call ${request.getOrNull(7)}: $millis ms, ${request.size} bytes in, ${reply.size} out")
            return reply
        }
        if (RemoteBuild.handles(request)) {
            val server = synchronized(this) { buildServer } ?: return RemoteBuild.failure("No project is open in the embedded backend.")
            return server.handle(request)
        }
        // The project tree rides the same channel: its requests open with a mark of their own.
        RemoteFileTree.modeOf(request)?.let { mode ->
            val host = synchronized(this) { backend } ?: return RemoteFileTree.failure("No project is open in the embedded backend.")
            return try {
                RemoteFileTree.answer(host.files.fileTree(mode))
            } catch (t: Throwable) {
                t.printStackTrace()
                RemoteFileTree.failure(t.message ?: t.toString())
            }
        }
        val target = synchronized(this) { dispatcher } ?: LayoutServiceDispatcher(NoProject, NoProject)
        return runBlocking { target.handle(request) }
    }

    /**
     * A build asked for by a file, until the app has a Run button of its own for this backend: `request` names
     * a task (`assemble:app:debug`), is consumed, and the build's transcript goes to the error stream.
     */
    private fun runRequestedBuild(request: File, host: IdeServicesBackend, services: IdeServices) {
        val task = runCatching { request.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        request.delete()
        Thread({
            try {
                val runner = host.buildRunnerFor(services)
                System.err.println("embedded build: $task; tasks on offer: ${runner.runTasks().map { it.id }}")
                val started = System.nanoTime()
                runner.runTask(task)
                var printed = 0
                var dumped = 0L
                var seenRunning = false
                while (true) {
                    val state = runner.buildState.value
                    for (line in state.log.drop(printed)) System.err.println("  [build] ${line.task ?: ""} ${line.message}")
                    printed = state.log.size
                    val running = state.status == dev.ide.ui.backend.RunStatus.Running
                    seenRunning = seenRunning || running
                    val waited = (System.nanoTime() - started) / 1_000_000
                    if (seenRunning && !running || !seenRunning && waited > 15_000) {
                        System.err.println("embedded build: $task ended ${state.status} after ${waited}ms")
                        break
                    }
                    // Where the time goes, for a build asked for this way: every busy thread, every ten seconds.
                    if (waited / 10_000 > dumped) {
                        dumped = waited / 10_000
                        System.err.println("---- threads at ${waited}ms ----")
                        for ((thread, stack) in Thread.getAllStackTraces()) {
                            if (stack.isEmpty() || thread === Thread.currentThread()) continue
                            val top = stack.first().toString()
                            if ("Unsafe.park" in top || "Object.wait" in top || "ReferenceQueue" in top || "Thread.sleep" in top) continue
                            System.err.println("  ${thread.name} [${thread.state}]")
                            stack.take(14).forEach { System.err.println("      $it") }
                        }
                    }
                    Thread.sleep(250)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }, "embedded-build").apply { isDaemon = true }.start()
    }

    @JvmStatic
    @Synchronized
    fun close() {
        dispatcher = null
        backend = null
        buildServer = null
        editorServer = null
        runCatching { engine?.close() }
        engine = null
        openRoot = null
    }
}
