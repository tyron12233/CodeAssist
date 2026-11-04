package dev.codeassist.dart

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import dev.ide.build.AlwaysRun
import dev.ide.build.ProgramIo
import dev.ide.build.RunConsole
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

/**
 * Starts the Flutter player (FlutterPlayerActivity, in this plugin's own app) and answers its Dart VM service
 * URI, which the player sends back once its engine has one.
 */
object FlutterPlayer {
    const val PACKAGE = "dev.codeassist.dart"
    private const val ACTIVITY = "dev.codeassist.dart.FlutterPlayerActivity"

    fun launch(timeoutMs: Long = 20_000): String {
        val context = appContext() ?: throw IOException("No Android context to start the Flutter player from.")
        val answer = CompletableFuture<String>()
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val uri = resultData?.getString(FlutterPlayerActivity.EXTRA_URI)
                if (resultCode == 0 && uri != null) answer.complete(uri)
                else answer.completeExceptionally(IOException("The Flutter player started without a VM service."))
            }
        }
        val intent = Intent()
            .setClassName(PACKAGE, ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(FlutterPlayerActivity.EXTRA_RECEIVER, receiver)
        context.startActivity(intent)
        return try {
            answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IOException("The Flutter player did not start within ${timeoutMs / 1000} seconds.")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * The IDE's application context. A plugin is handed none (its engine facet has no Android API), but this
     * code runs inside the IDE's process, the IDE's or its `:build` one, and the process has an application.
     */
    private fun appContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context?
    }.getOrNull()
}

/**
 * `flutter run` for the device itself: compile the app, run it in the player, and keep it hot-reloading.
 *
 * The run lasts as long as the app: every save of a `.dart` file in the package hot-reloads it (state kept),
 * a pubspec change gets `pub get` and a hot restart, and the console takes `r` (reload), `R` (restart) and `q`
 * (quit), as `flutter run` does. What the app prints shows in the console. Stop, `q` or closing the app ends it.
 */
internal class FlutterRunTask(
    override val name: TaskName,
    private val pkg: Path,
    private val sdk: DartSdk,
    private val flutter: FlutterSdk,
    private val pub: Pub,
    private val console: RunConsole,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    private lateinit var out: (String) -> Unit

    override suspend fun execute(ctx: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        val io: ProgramIo? = console.io
        out = io?.let { c -> { line: String -> c.stdout(line + "\n") } } ?: ctx.logger()
        io?.started()
        val result = try {
            session(io)
        } catch (e: IOException) {
            out(e.message ?: e.toString())
            TaskResult.Failed(e.message ?: "The Flutter run failed")
        }
        io?.exited(if (result is TaskResult.Success) 0 else 1)
        result
    }

    private suspend fun session(io: ProgramIo?): TaskResult {
        (sdk.prepare() as? DartSdk.Status.Unavailable)?.let { return TaskResult.Failed(it.reason).also { _ -> out(it.reason) } }
        (flutter.prepare() as? FlutterSdk.Status.Unavailable)?.let { return TaskResult.Failed(it.reason).also { _ -> out(it.reason) } }
        val name = DartPackages.nameOf(String(Files.readAllBytes(pkg.resolve(DartPackages.PUBSPEC))))
            ?: return fail("pubspec.yaml has no name")
        if (!Files.isRegularFile(pkg.resolve("lib/main.dart"))) return fail("A Flutter app needs lib/main.dart.")
        val entry = "package:$name/main.dart"
        if (DartPackages.needsPubGet(pkg) && !pubGet()) return fail("pub get failed")

        val work = pkg.resolve(".dart_tool/codeassist/flutter")
        val frontend = FrontendServer(sdk, flutter, pkg, work.resolve("app.dill"))
        var vm: VmService? = null
        try {
            frontend.start()
            out("Compiling $entry ...")
            val started = System.nanoTime()
            val first = frontend.compile(entry)
            first.messages.forEach(out)
            if (!first.ok) return fail("The app did not compile (${first.errors} error(s)).")
            frontend.accept()
            out("Compiled in ${ms(started)} ms.")

            out("Starting the Flutter player ...")
            val uri = FlutterPlayer.launch()
            vm = connect(uri)
            val app = AppSession(vm, frontend, entry)
            vm.onEvent = { stream, event -> if (stream == "Stdout" || stream == "Stderr") printed(event) }
            for (stream in listOf("Stdout", "Stderr")) runCatching { vm.call("streamListen", mapOf("streamId" to stream)) }
            val uploaded = System.nanoTime()
            app.uploadAssets()
            app.upload(first.dill!!, "app.dill")
            app.runInView("app.dill")
            out("Running in ${ms(uploaded)} ms. Saving a file hot-reloads it; type r (reload), R (restart) or q (quit).")

            val commands = LinkedBlockingQueue<String>()
            io?.stdin?.let { readCommands(it, commands) }
            var seen = snapshot()
            while (currentCoroutineContext().isActive && !vm.isClosed) {
                delay(POLL_MS)
                when (commands.poll()?.trim()) {
                    "r" -> app.reload(changedSince(seen).also { seen = snapshot() })
                    "R" -> app.restart(changedSince(seen).also { seen = snapshot() })
                    "q" -> { quit(vm); break }
                    else -> {
                        val now = snapshot()
                        if (now == seen) continue
                        val changed = now.keys.filter { now[it] != seen[it] }
                        seen = now
                        if (changed.any { it.fileName.toString() == DartPackages.PUBSPEC }) {
                            out("pubspec.yaml changed: pub get and a hot restart.")
                            if (pubGet()) { app.uploadAssets(); app.restart(emptyList()) }
                        } else {
                            app.reload(changed.filter { it.toString().endsWith(".dart") })
                        }
                    }
                }
            }
            if (vm.isClosed) out("The app was closed.")
            return TaskResult.Success
        } finally {
            if (vm != null && !vm.isClosed) quit(vm)
            vm?.close()
            frontend.close()
        }
    }

    /** What one app run needs between reloads: the VM service, the compiler, the DevFS. */
    private inner class AppSession(private val vm: VmService, private val frontend: FrontendServer, private val entry: String) {
        private val fs = "codeassist"
        private val fsUri: String

        init {
            runCatching { vm.call("_deleteDevFS", mapOf("fsName" to fs)) }
            fsUri = vm.call("_createDevFS", mapOf("fsName" to fs)).string("uri") ?: throw IOException("the app made no DevFS")
        }

        fun upload(file: Path, path: String) = vm.upload(fs, path, file)

        fun uploadAssets() {
            val bundle = FlutterAssets.build(pkg, flutter.baseAssets)
            for ((path, file) in bundle.files) upload(file, "flutter_assets/$path")
            val staged = pkg.resolve(".dart_tool/codeassist/flutter/generated")
            Files.createDirectories(staged)
            for ((path, bytes) in bundle.generated) {
                val file = staged.resolve(path)
                Files.write(file, bytes)
                upload(file, "flutter_assets/$path")
            }
        }

        fun runInView(dill: String) {
            vm.call("_flutter.runInView", mapOf(
                "viewId" to view().string("id"),
                "mainScript" to fsUri + dill,
                "assetDirectory" to Paths.get(URI(fsUri + "flutter_assets")).toString(),
            ))
        }

        fun reload(changed: List<Path>) {
            if (changed.isEmpty()) { out("Nothing changed."); return }
            val started = System.nanoTime()
            val delta = frontend.recompile(entry, changed.map { it.toUri().toString() })
            delta.messages.forEach(out)
            if (!delta.ok) {
                frontend.reject()
                out("Not reloaded: ${delta.errors} compile error(s).")
                return
            }
            upload(delta.dill!!, "app.dill.incremental.dill")
            val isolate = (view()["isolate"] as? JsonObject)?.string("id") ?: throw IOException("the app has no isolate")
            val report = vm.call("reloadSources", mapOf("isolateId" to isolate, "rootLibUri" to fsUri + "app.dill.incremental.dill"))
            if ((report["success"] as? JsonPrimitive)?.booleanOrNull != true) {
                frontend.reject()
                val notices = (report["notices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("message") }
                out("Hot reload was rejected: ${notices.joinToString("; ").ifEmpty { "the VM refused the change" }}. Type R to restart.")
                return
            }
            frontend.accept()
            vm.call("ext.flutter.reassemble", mapOf("isolateId" to isolate))
            val details = report["details"] as? JsonObject
            out("Reloaded ${details?.string("loadedLibraryCount") ?: "?"} of ${details?.string("finalLibraryCount") ?: "?"} libraries in ${ms(started)} ms.")
        }

        fun restart(changed: List<Path>) {
            val started = System.nanoTime()
            val full = frontend.recompileFull(entry, changed.map { it.toUri().toString() })
            full.messages.forEach(out)
            if (!full.ok) {
                frontend.reject()
                out("Not restarted: ${full.errors} compile error(s).")
                return
            }
            frontend.accept()
            upload(full.dill!!, "app.dill")
            runInView("app.dill")
            out("Restarted in ${ms(started)} ms.")
        }

        private fun view(): JsonObject {
            val views = vm.call("_flutter.listViews")["views"] as? JsonArray
            return views?.firstOrNull() as? JsonObject ?: throw IOException("the app has no Flutter view")
        }
    }

    private fun pubGet(): Boolean {
        out("> dart pub get")
        return pub.run(pkg, "get", out = out) == 0
    }

    private fun fail(message: String): TaskResult {
        out(message)
        return TaskResult.Failed(message)
    }

    /** The VM service answers a moment after the engine reports it; try a few times. */
    private suspend fun connect(uri: String): VmService {
        var last: IOException? = null
        repeat(20) {
            try {
                return VmService.connect(uri)
            } catch (e: IOException) {
                last = e
                delay(250)
            }
        }
        throw IOException("Could not reach the Flutter player's VM service at $uri: ${last?.message}")
    }

    /** Ask the app to exit, which ends the player too; a run that stopped should not leave it behind. */
    private fun quit(vm: VmService) {
        runCatching {
            val views = vm.call("_flutter.listViews", timeoutMs = 2_000)["views"] as? JsonArray
            val isolate = ((views?.firstOrNull() as? JsonObject)?.get("isolate") as? JsonObject)?.string("id") ?: return
            vm.call("ext.flutter.exit", mapOf("isolateId" to isolate), timeoutMs = 2_000)
        }
    }

    /** What the app printed (a `print`, an uncaught error), from the VM's Stdout and Stderr streams. */
    private fun printed(event: JsonObject) {
        val bytes = event.string("bytes") ?: return
        val text = runCatching { String(Base64.getDecoder().decode(bytes)) }.getOrNull() ?: return
        text.trimEnd('\n').split('\n').forEach(out)
    }

    private fun readCommands(stdin: java.io.InputStream, into: LinkedBlockingQueue<String>) {
        Thread({
            runCatching { stdin.bufferedReader().forEachLine { into.put(it) } }
        }, "flutter-run-commands").apply { isDaemon = true }.start()
    }

    /** The modification times of the package's Dart files and pubspec, outside `.dart_tool` and `build`. */
    private fun snapshot(): Map<Path, Long> {
        val files = Files.walk(pkg).use { walk ->
            walk.filter { p ->
                val rel = pkg.relativize(p).toString()
                !rel.startsWith(".dart_tool") && !rel.startsWith("build") && !rel.startsWith(".") &&
                    (rel.endsWith(".dart") || rel == DartPackages.PUBSPEC) && Files.isRegularFile(p)
            }.collect(Collectors.toList())
        }
        return files.associateWith { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
    }

    private fun changedSince(before: Map<Path, Long>): List<Path> {
        val now = snapshot()
        return now.keys.filter { now[it] != before[it] && it.toString().endsWith(".dart") }
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val POLL_MS = 400L
    }
}
