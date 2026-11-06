package dev.codeassist.dart

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The preview pane's side of a Flutter preview, in the IDE's process: the gallery of ONE file's `@Preview`
 * widgets, compiled here and run in an engine the plugin's preview service draws into the pane.
 *
 * It lives as long as the pane shows a package. Switching to another file of the package rewrites the gallery
 * and hot-reloads it; saving any file of the package hot-reloads it, keeping the previews' state, as Run does.
 * The engine arrives separately ([engineReady]), from the pane's surface once the service has made one; a new
 * engine (the service's process died and the pane bound it again) gets the program restarted in it.
 *
 * Kept light: its compiler stops after [FlutterSession.IDLE_MS] without a save and starts again (from the kernel it
 * left) on the next one, and the whole preview pauses while a Run of the same package is going.
 */
internal class FlutterPreviewController(
    val pkg: Path,
    private val tools: DartPlugin.Tools,
) {
    /** What the pane shows over the previews ([message], when there is one) and in its problem chip. */
    data class State(val message: String?, val problems: List<String> = emptyList())

    private val _state = MutableStateFlow(State("Preparing the preview ..."))
    val state: StateFlow<State> get() = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engines = Channel<Engine>(Channel.CONFLATED)
    /** Wakes the session loop: a save (through its [PackageWatcher]), another file shown, a new engine. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    // One place per package, kept: the next pane on this package starts its compiler from the kernel left here.
    private val work = FlutterSession.paneWork(pkg)
    private val galleryFile = work.resolve("gallery.dart")

    @Volatile private var file: Path? = null
    @Volatile private var dark: Boolean? = null
    @Volatile private var dirty = true
    @Volatile private var session: FlutterSession? = null
    private var packageName = ""
    private var job: Job? = null

    private class Engine(val uri: String, val isolate: String?)

    /**
     * Told when the preview pauses for a Run of the package (true) and resumes after it (false), so the pane can let
     * its engine go meanwhile. Called off the main thread.
     */
    @Volatile var onPause: (Boolean) -> Unit = {}

    /** Show [path]'s previews in the [dark] or light scheme. Cheap when nothing changed. */
    fun show(path: String, dark: Boolean) {
        val p = Paths.get(path)
        if (p == file && dark == this.dark) return
        file = p
        this.dark = dark
        dirty = true
        wake.trySend(Unit)
        if (job == null) job = scope.launch { run() }
    }

    /** The service made an engine for the pane and it is listening at [uri]. */
    fun engineReady(uri: String, isolate: String?) {
        engines.trySend(Engine(uri, isolate))
        wake.trySend(Unit)
    }

    /** The pane's engine could not be had. */
    fun engineFailed(reason: String) {
        _state.value = State(reason, listOf(reason))
    }

    fun close() {
        scope.cancel()
        // The compiler blocks on its answers; closing it is what unblocks a run that is waiting on one.
        session?.close()
    }

    private suspend fun run() {
        try {
            session()
        } catch (e: IOException) {
            if (scope.isActive) _state.value = State(e.message ?: "The preview stopped.", listOf(e.message ?: e.toString()))
        } finally {
            session?.close()
        }
    }

    private suspend fun session() {
        (tools.sdk.prepare() as? DartSdk.Status.Unavailable)?.let { return fail(it.reason) }
        (tools.flutter.prepare() as? FlutterSdk.Status.Unavailable)?.let { return fail(it.reason) }
        packageName = DartPackages.nameOf(String(Files.readAllBytes(pkg.resolve(DartPackages.PUBSPEC))))
            ?: return fail("pubspec.yaml has no name")
        if (DartPackages.needsPubGet(pkg)) {
            _state.value = State("Getting packages ...")
            if (!pubGet()) return fail("pub get failed: open pubspec.yaml or Run to see why.")
        }

        PackageWatcher(pkg, tools.bus, wake).use { watcher ->
            while (scope.isActive) {
                if (FlutterSession.runActive(pkg)) pauseWhileRunning(watcher)
                live(watcher)
            }
        }
    }

    /**
     * A Run of this package holds a compiler and a Flutter engine of its own; the preview gives up both until the
     * Run ends, then comes back with a fresh engine and a compiler that starts from the kernel it left.
     */
    private suspend fun pauseWhileRunning(watcher: PackageWatcher) {
        _state.value = State("Paused while the app runs. The preview comes back when the Run ends.")
        onPause(true)
        while (engines.tryReceive().isSuccess) { /* engines from before the pause are gone with it */ }
        while (scope.isActive && FlutterSession.runActive(pkg)) watcher.await()
        watcher.changes() // whatever changed meanwhile is compiled from scratch anyway
        dirty = true
        onPause(false)
    }

    /** The preview, until a Run of the package starts (then it returns, its compiler closed) or the pane goes. */
    private suspend fun live(watcher: PackageWatcher) {
        // The first compile, again after every save until there is one that compiles.
        var compiled: Path? = null
        var app: FlutterSession? = null
        while (compiled == null) {
            if (FlutterSession.runActive(pkg)) return
            writeGallery()
            _state.value = State(COMPILING)
            app = FlutterSession(
                pkg, tools.sdk, tools.flutter, galleryFile.toUri().toString(), work, ::log, ::galleryChanges,
                siblings = listOf(FlutterSession.runWork(pkg).resolve("app.dill")),
            )
            session = app
            // The first compile after the IDE starts races the analysis server's own first pass over the framework
            // and can take tens of seconds on a phone; past a few seconds, say why the pane is still waiting.
            val slow = scope.launch {
                delay(SLOW_COMPILE_MS)
                if (_state.value.message == COMPILING) {
                    _state.value = State("$COMPILING\nThe first compile after opening a project is slow while Dart analyses it.")
                }
            }
            val first = try { app.compile() } finally { slow.cancel() }
            if (first.ok) {
                compiled = first.dill
            } else {
                app.close()
                _state.value = State("The previews do not compile. Save a fix to try again.", errors(first))
                while (scope.isActive && watcher.changes().isEmpty() && !dirty && !FlutterSession.runActive(pkg)) watcher.await()
                dirty = false
            }
        }
        app!!
        try {
            live(watcher, app, compiled)
        } finally {
            app.close()
            session = null
        }
    }

    private suspend fun live(watcher: PackageWatcher, app: FlutterSession, compiled: Path) {
        _state.value = State("Starting the Flutter engine ...")
        var first: Engine? = null
        while (first == null) {
            first = engines.tryReceive().getOrNull()
            if (first == null) {
                if (!scope.isActive || FlutterSession.runActive(pkg)) return
                watcher.await()
            }
        }
        var engine: Engine = first
        app.runIn(FlutterSession.connect(engine.uri), engine.isolate, compiled)
        _state.value = State(null)
        dirty = false

        while (scope.isActive) {
            watcher.await()
            if (FlutterSession.runActive(pkg)) return
            app.releaseIfIdle()
            engines.tryReceive().getOrNull()?.let { next ->
                // A new engine: the old one went with its process. Start the program in it from scratch.
                engine = next
                _state.value = State("Restarting the preview ...")
                val full = app.fullKernel(watcher.changes())
                if (!full.ok) { _state.value = State("The previews do not compile.", errors(full)); return@let }
                app.runIn(FlutterSession.connect(engine.uri), engine.isolate, full.dill!!)
                _state.value = State(null)
                dirty = false
            }
            if (app.engineGone) {
                // The pane went (its engine with it) or the engine's process died; the next pane's engine, or
                // the rebound one, arrives through [engines].
                _state.value = State("Starting the Flutter engine ...")
                continue
            }
            val changed = watcher.changes()
            if (changed.isEmpty() && !dirty) continue
            dirty = false
            val outcome = if (changed.any { it.fileName.toString() == DartPackages.PUBSPEC }) {
                _state.value = State("Getting packages ...")
                if (!pubGet()) { _state.value = State(null, listOf("pub get failed")); continue }
                app.uploadAssets()
                app.restart(emptyList())
            } else {
                app.reload(changed.filter { it.toString().endsWith(".dart") && Files.isRegularFile(it) })
            }
            _state.value = State(null, outcome.problems)
        }
    }

    /** The gallery for the file being shown, rewritten when what it holds changed. */
    private fun writeGallery(): Boolean {
        val shown = file ?: return false
        val lib = pkg.resolve("lib")
        // Only the shown file's previews, so only that file is read: not the whole of lib/ on every reload.
        val previews = if (shown.startsWith(lib)) {
            val library = "package:$packageName/" + lib.relativize(shown).joinToString("/") { it.toString() }
            val text = runCatching { String(Files.readAllBytes(shown)) }.getOrDefault("")
            if (DartPreviews.isLibraryWithPreviews(text)) DartPreviews.scan(text).map { it.copy(library = library) } else emptyList()
        } else emptyList()
        val empty = if (shown.startsWith(lib)) "No @Preview widgets in ${shown.fileName} yet."
        else "Previews are shown for files in lib/."
        return DartPreviews.write(galleryFile, DartPreviews.gallery(previews, pane = true, dark = dark, empty = empty))
    }

    private fun galleryChanges(): List<Path> = if (writeGallery()) listOf(galleryFile) else emptyList()

    private fun pubGet(): Boolean = tools.pub.run(pkg, "get", out = ::log) == 0

    private fun errors(result: FrontendServer.Result): List<String> =
        result.messages.filter { ": Error: " in it || it.startsWith("Error: ") }
            .map { it.substringAfter("$pkg/") }
            .ifEmpty { listOf("${result.errors} compile error(s)") }

    private fun fail(reason: String) {
        _state.value = State(reason, listOf(reason))
    }

    private fun log(line: String) = tools.log.info("preview: $line")

    private companion object {
        const val COMPILING = "Compiling the previews ..."
        const val SLOW_COMPILE_MS = 6_000L
    }
}
