package dev.codeassist.dart

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * One Flutter program kept running and hot-reloading: its incremental compiler and, once it has an engine to
 * run in, that engine's VM service and DevFS. What `flutter run` keeps between reloads, shared by the Run
 * console ([FlutterRunTask]) and the editor's preview pane ([FlutterPreviewController]).
 *
 * Blocking throughout: every call talks to the compiler or the VM and waits for its answer.
 */
internal class FlutterSession(
    private val pkg: Path,
    private val sdk: DartSdk,
    private val flutter: FlutterSdk,
    /** What runs: `package:app/main.dart`, or a generated gallery's file URI. */
    private val entry: String,
    /** The compiler's working directory. A Run and a preview pane each have their own. */
    work: Path,
    private val out: (String) -> Unit,
    /** Files a reload must recompile besides the ones that were edited: a gallery rewritten just now. */
    private val alsoChanged: () -> List<Path> = { emptyList() },
    /** Other compiles of this package whose kernels a cold compiler can start from (see [FrontendServer.warm]). */
    siblings: List<Path> = emptyList(),
) : AutoCloseable {
    /** What a reload or restart came to, and the compiler's errors when it did not happen. */
    class Outcome(val ok: Boolean, val problems: List<String> = emptyList())

    private val frontend = FrontendServer(sdk, flutter, pkg, work.resolve("app.dill"), siblings + seed(sdk))
    private var vm: VmService? = null
    private var fsUri = ""
    private var lastUsed = System.nanoTime()

    /** The compiler was stopped for being idle ([releaseIfIdle]); the next reload or restart starts it again. */
    @Volatile var released = false
        private set
    private var viewId: String? = null
    private var isolateHint: String? = null

    /** Whether the engine the program ran in has gone (closed, or killed with its process). */
    val engineGone: Boolean get() = vm?.isClosed ?: true

    /**
     * Start the compiler and compile the whole program. Accepted when it compiled, so reloads build on it.
     *
     * A compiler that had no kernel to start from is replaced at once by one that starts from what it just wrote:
     * a second or so, once, for about 200 MB less for the rest of the session. Its kernel also becomes the
     * plugin-wide seed when there is none, so no later compile of any Flutter app starts cold.
     */
    fun compile(): FrontendServer.Result {
        lastUsed = System.nanoTime()
        frontend.start()
        val first = frontend.compile(entry)
        if (!first.ok) return first
        frontend.accept()
        if (frontend.warm) return first
        val compiled = first.dill!!
        saveSeed(compiled)
        frontend.close()
        frontend.start()
        val again = frontend.compile(entry)
        if (again.ok) frontend.accept()
        return again
    }

    private fun saveSeed(dill: Path) {
        val target = seed(sdk)
        if (Files.isRegularFile(target)) return
        runCatching {
            Files.createDirectories(target.parent)
            val partial = target.resolveSibling(target.fileName.toString() + ".partial")
            Files.copy(dill, partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /**
     * Run the program in the engine whose VM service is [vm]: a DevFS for its files, then the kernel [dill]
     * started in the engine's view. [isolate] names the isolate the engine started with, which picks its view
     * when the VM has several (two engines in one process share one VM).
     */
    fun runIn(vm: VmService, isolate: String?, dill: Path) {
        this.vm?.takeIf { it !== vm }?.close()
        this.vm = vm
        viewId = null
        isolateHint = isolate
        runCatching { vm.call("_deleteDevFS", mapOf("fsName" to FS)) }
        fsUri = vm.call("_createDevFS", mapOf("fsName" to FS)).string("uri") ?: throw IOException("the app made no DevFS")
        val started = System.nanoTime()
        uploadAssets()
        val assets = System.nanoTime()
        vm.upload(FS, "app.dill", dill)
        val kernel = System.nanoTime()
        runInView("app.dill")
        out("Started: assets ${(assets - started) / 1_000_000} ms, kernel ${(kernel - assets) / 1_000_000} ms, run ${ms(kernel)} ms.")
    }

    /** A full kernel of the program as it is now, for an engine that has not run it yet. */
    fun fullKernel(edited: List<Path> = emptyList()): FrontendServer.Result {
        lastUsed = System.nanoTime()
        if (released) return revive()
        val full = frontend.recompileFull(entry, (edited + alsoChanged()).map { it.toUri().toString() })
        if (full.ok) frontend.accept() else frontend.reject()
        return full
    }

    /**
     * Stop the compiler if nothing was compiled for [idleMs]: it holds some 350 MB doing nothing. Answers whether it
     * stopped. The next reload starts a compiler from the kernel it left (about a second, see [FrontendServer.warm]).
     */
    fun releaseIfIdle(idleMs: Long = IDLE_MS): Boolean {
        if (released || (System.nanoTime() - lastUsed) / 1_000_000 < idleMs) return false
        frontend.close()
        released = true
        out("The compiler was stopped after ${idleMs / 60_000} idle minutes; the next save starts it again.")
        return true
    }

    /**
     * A compiler again after [releaseIfIdle], and a full kernel of the program as it is now: it starts from the kernel
     * the last one wrote and checks that against the sources, so whatever was saved meanwhile is in it. It is
     * accepted as the base for the reloads after it. Left released when the program does not compile.
     */
    private fun revive(): FrontendServer.Result {
        alsoChanged() // a gallery brought up to date before it is compiled
        frontend.start()
        val full = frontend.compile(entry)
        if (full.ok) {
            frontend.accept()
            released = false
        } else {
            frontend.close()
        }
        return full
    }

    /** The app's assets, its fonts and the framework's own, into the DevFS where the engine reads them. */
    fun uploadAssets() {
        val vm = vm ?: return
        val bundle = FlutterAssets.build(pkg, flutter.baseAssets)
        for ((path, file) in bundle.files) vm.upload(FS, "flutter_assets/$path", file)
        val staged = pkg.resolve(".dart_tool/codeassist/flutter/generated")
        Files.createDirectories(staged)
        for ((path, bytes) in bundle.generated) {
            val file = staged.resolve(path)
            Files.write(file, bytes)
            vm.upload(FS, "flutter_assets/$path", file)
        }
    }

    /** Hot reload: recompile what changed, hand the VM the delta, and rebuild the widget tree. State is kept. */
    fun reload(edited: List<Path>): Outcome {
        val vm = vm ?: throw IOException("the app is not running")
        lastUsed = System.nanoTime()
        if (released) return reloadRevived(vm)
        val changed = edited + alsoChanged()
        if (changed.isEmpty()) { out("Nothing changed."); return Outcome(true) }
        val started = System.nanoTime()
        val delta = frontend.recompile(entry, changed.map { it.toUri().toString() })
        delta.messages.forEach(out)
        if (!delta.ok) {
            frontend.reject()
            out("Not reloaded: ${delta.errors} compile error(s).")
            return Outcome(false, errors(delta))
        }
        val compiled = System.nanoTime()
        vm.upload(FS, "app.dill.incremental.dill", delta.dill!!)
        val uploaded = System.nanoTime()
        val isolate = (view()["isolate"] as? JsonObject)?.string("id") ?: throw IOException("the app has no isolate")
        val report = vm.call("reloadSources", mapOf("isolateId" to isolate, "rootLibUri" to fsUri + "app.dill.incremental.dill"))
        val reloaded = System.nanoTime()
        if ((report["success"] as? JsonPrimitive)?.booleanOrNull != true) {
            frontend.reject()
            val notices = (report["notices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("message") }
            val why = notices.joinToString("; ").ifEmpty { "the VM refused the change" }
            out("Hot reload was rejected: $why. Type R to restart.")
            return Outcome(false, listOf("Hot reload was rejected: $why"))
        }
        frontend.accept()
        vm.call("ext.flutter.reassemble", mapOf("isolateId" to isolate))
        val details = report["details"] as? JsonObject
        out("Reloaded ${details?.string("loadedLibraryCount") ?: "?"} of ${details?.string("finalLibraryCount") ?: "?"} libraries in ${ms(started)} ms " +
            "(compile ${(compiled - started) / 1_000_000}, upload ${(uploaded - compiled) / 1_000_000}, " +
            "reload ${(reloaded - uploaded) / 1_000_000}, rebuild ${ms(reloaded)}).")
        return Outcome(true)
    }

    /**
     * The first reload after [releaseIfIdle]: the new compiler has no delta to give, so the VM gets the whole
     * program, which it reloads like any other change (state kept). Should it refuse, the app restarts instead.
     */
    private fun reloadRevived(vm: VmService): Outcome {
        val started = System.nanoTime()
        val full = revive()
        full.messages.forEach(out)
        if (!full.ok) {
            out("Not reloaded: ${full.errors} compile error(s).")
            return Outcome(false, errors(full))
        }
        vm.upload(FS, "app.dill", full.dill!!)
        val isolate = (view()["isolate"] as? JsonObject)?.string("id") ?: throw IOException("the app has no isolate")
        val report = vm.call("reloadSources", mapOf("isolateId" to isolate, "rootLibUri" to fsUri + "app.dill"))
        if ((report["success"] as? JsonPrimitive)?.booleanOrNull == true) {
            vm.call("ext.flutter.reassemble", mapOf("isolateId" to isolate))
            out("Reloaded with a new compiler in ${ms(started)} ms.")
        } else {
            runInView("app.dill")
            out("Restarted with a new compiler in ${ms(started)} ms (the VM would not reload the whole program).")
        }
        return Outcome(true)
    }

    /** Hot restart: the whole program recompiled and started again in the same view. State is lost. */
    fun restart(edited: List<Path>): Outcome {
        val vm = vm ?: throw IOException("the app is not running")
        val started = System.nanoTime()
        val full = fullKernel(edited)
        full.messages.forEach(out)
        if (!full.ok) {
            out("Not restarted: ${full.errors} compile error(s).")
            return Outcome(false, errors(full))
        }
        val compiled = System.nanoTime()
        vm.upload(FS, "app.dill", full.dill!!)
        val uploaded = System.nanoTime()
        runInView("app.dill")
        out("Restarted in ${ms(started)} ms (compile ${(compiled - started) / 1_000_000}, upload ${(uploaded - compiled) / 1_000_000}, start ${ms(uploaded)}).")
        return Outcome(true)
    }

    override fun close() {
        vm?.close()
        vm = null
        frontend.close()
    }

    private fun runInView(dill: String) {
        val vm = vm ?: return
        vm.call("_flutter.runInView", mapOf(
            "viewId" to view().string("id"),
            "mainScript" to fsUri + dill,
            "assetDirectory" to Paths.get(URI(fsUri + "flutter_assets")).toString(),
        ))
    }

    /**
     * This program's Flutter view. The first time, the one whose isolate is [isolateHint] (or the only one); after
     * that the same view by its id, since running a program in a view gives the view a new isolate.
     */
    private fun view(timeoutMs: Long = 60_000): JsonObject {
        val vm = vm ?: throw IOException("the app is not running")
        val views = (vm.call("_flutter.listViews", timeoutMs = timeoutMs)["views"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
        val chosen = viewId?.let { id ->
            views.firstOrNull { it.string("id") == id } ?: throw IOException("the app's Flutter view is gone")
        } ?: isolateHint?.let { iso -> views.firstOrNull { (it["isolate"] as? JsonObject)?.string("id") == iso } }
            ?: views.firstOrNull()
            ?: throw IOException("the app has no Flutter view")
        viewId = chosen.string("id")
        return chosen
    }

    private fun errors(result: FrontendServer.Result): List<String> {
        val errors = result.messages.filter { ": Error: " in it || it.startsWith("Error: ") }
            .map { line -> line.substringAfter(pkg.toString() + "/").removePrefix(pkg.toUri().toString()) }
        return errors.ifEmpty { listOf("${result.errors} compile error(s)") }
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    companion object {
        private const val FS = "codeassist"

        /** How long a compiler may sit unused before [releaseIfIdle] stops it. */
        const val IDLE_MS = 2 * 60_000L

        /**
         * The plugin-wide seed: a kernel of some Flutter app built with this Flutter, which a compiler with nothing
         * better starts from. Mostly the framework, which every app shares; its app libraries are checked against
         * the sources like any other and simply recompiled.
         */
        fun seed(sdk: DartSdk): Path = sdk.home.resolve("flutter-seed-${FlutterSdk.VERSION}.dill")

        /** Where the Run compiles a package. */
        fun runWork(pkg: Path): Path = pkg.resolve(".dart_tool/codeassist/flutter")

        /**
         * A Run's heartbeat: present while a Run of the package is going, its time moved on every couple of seconds.
         * A file and not a flag in memory because the Run may be in the build process, not the IDE's; a time and not
         * just presence because a Run whose process died leaves the file behind.
         */
        fun runMarker(pkg: Path): Path = runWork(pkg).resolve("running")

        /** Whether a Run of [pkg] is going now (its [runMarker] was touched in the last ten seconds). */
        fun runActive(pkg: Path): Boolean = runCatching {
            System.currentTimeMillis() - Files.getLastModifiedTime(runMarker(pkg)).toMillis() < 10_000
        }.getOrDefault(false)

        /** Where the preview pane compiles a package. */
        fun paneWork(pkg: Path): Path = pkg.resolve(".dart_tool/codeassist/pane")

        /** The VM service answers a moment after the engine reports it; try a few times. */
        suspend fun connect(uri: String): VmService {
            var last: IOException? = null
            repeat(20) {
                try {
                    return VmService.connect(uri)
                } catch (e: IOException) {
                    last = e
                    delay(250)
                }
            }
            throw IOException("Could not reach the Flutter engine's VM service at $uri: ${last?.message}")
        }

        internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
