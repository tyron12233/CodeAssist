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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The preview pane's side of a Flutter preview, in the IDE's process: the gallery of ONE file's `@Preview`
 * widgets, compiled here and run in an engine the plugin's preview service draws into the pane.
 *
 * It lives as long as the pane shows a package. Switching to another file of the package rewrites the gallery
 * and hot-reloads it; saving any file of the package hot-reloads it, keeping the previews' state, as Run does.
 * The engine arrives separately ([engineReady]), from the pane's surface once the service has made one; a new
 * engine (the service's process died and the pane bound it again) gets the program restarted in it.
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
    private val work = pkg.resolve(".dart_tool/codeassist/pane/${ids.incrementAndGet()}")
    private val galleryFile = work.resolve("gallery.dart")

    @Volatile private var file: Path? = null
    @Volatile private var dark: Boolean? = null
    @Volatile private var dirty = true
    @Volatile private var session: FlutterSession? = null
    private var packageName = ""
    private var job: Job? = null

    private class Engine(val uri: String, val isolate: String?)

    /** Show [path]'s previews in the [dark] or light scheme. Cheap when nothing changed. */
    fun show(path: String, dark: Boolean) {
        val p = Paths.get(path)
        if (p == file && dark == this.dark) return
        file = p
        this.dark = dark
        dirty = true
        if (job == null) job = scope.launch { run() }
    }

    /** The service made an engine for the pane and it is listening at [uri]. */
    fun engineReady(uri: String, isolate: String?) {
        engines.trySend(Engine(uri, isolate))
    }

    /** The pane's engine could not be had. */
    fun engineFailed(reason: String) {
        _state.value = State(reason, listOf(reason))
    }

    fun close() {
        scope.cancel()
        // The compiler blocks on its answers; closing it is what unblocks a run that is waiting on one.
        session?.close()
        runCatching { work.toFile().deleteRecursively() }
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

        // The first compile, again after every save until there is one that compiles.
        var seen = FlutterSession.snapshot(pkg)
        var compiled: Path? = null
        var app: FlutterSession? = null
        while (compiled == null) {
            writeGallery()
            _state.value = State("Compiling the previews ...")
            app = FlutterSession(pkg, tools.sdk, tools.flutter, galleryFile.toUri().toString(), work, ::log, ::galleryChanges)
            session = app
            val first = app.compile()
            if (first.ok) {
                compiled = first.dill
            } else {
                app.close()
                _state.value = State("The previews do not compile. Save a fix to try again.", errors(first))
                seen = waitForChange(seen)
            }
        }
        app!!

        _state.value = State("Starting the Flutter engine ...")
        var engine = engines.receive()
        app.runIn(FlutterSession.connect(engine.uri), engine.isolate, compiled)
        _state.value = State(null)
        dirty = false

        while (scope.isActive) {
            delay(POLL_MS)
            engines.tryReceive().getOrNull()?.let { next ->
                // A new engine: the old one went with its process. Start the program in it from scratch.
                engine = next
                _state.value = State("Restarting the preview ...")
                val full = app.fullKernel()
                if (!full.ok) { _state.value = State("The previews do not compile.", errors(full)); return@let }
                app.runIn(FlutterSession.connect(engine.uri), engine.isolate, full.dill!!)
                _state.value = State(null)
                seen = FlutterSession.snapshot(pkg)
                dirty = false
            }
            if (app.engineGone) {
                // The pane went (its engine with it) or the engine's process died; the next pane's engine, or
                // the rebound one, arrives through [engines].
                _state.value = State("Starting the Flutter engine ...")
                continue
            }
            val now = FlutterSession.snapshot(pkg)
            val changed = FlutterSession.changed(seen, now)
            if (changed.isEmpty() && !dirty) continue
            seen = now
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
        val previews = if (shown.startsWith(lib)) {
            val library = "package:$packageName/" + lib.relativize(shown).joinToString("/") { it.toString() }
            DartPreviews.find(pkg, packageName).filter { it.library == library }
        } else emptyList()
        val empty = if (shown.startsWith(lib)) "No @Preview widgets in ${shown.fileName} yet."
        else "Previews are shown for files in lib/."
        return DartPreviews.write(galleryFile, DartPreviews.gallery(previews, pane = true, dark = dark, empty = empty))
    }

    private fun galleryChanges(): List<Path> = if (writeGallery()) listOf(galleryFile) else emptyList()

    private suspend fun waitForChange(before: Map<Path, Long>): Map<Path, Long> {
        while (scope.isActive) {
            delay(POLL_MS)
            val now = FlutterSession.snapshot(pkg)
            if (now != before || dirty) return now.also { dirty = false }
        }
        return before
    }

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
        const val POLL_MS = 400L
        val ids = AtomicInteger()
    }
}
