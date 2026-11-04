package dev.codeassist.dart

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import dev.codeassist.dart.FlutterSession.Companion.string
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
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Starts the Flutter player (FlutterPlayerActivity, in this plugin's own app) and answers its Dart VM service
 * URI and the isolate its engine started, which the player sends back once its engine has them.
 */
object FlutterPlayer {
    const val PACKAGE = "dev.codeassist.dart"
    /** The player's gate, which checks that CodeAssist is the caller (see FlutterPlayerLauncher). */
    private const val ACTIVITY = "dev.codeassist.dart.FlutterPlayerLauncher"

    class Started(val uri: String, val isolate: String?, private val control: android.os.IBinder?) {
        /** End the player: its task closed, its process gone. Safe to call again, or once it is already gone. */
        fun quit() {
            val binder = control ?: return
            runCatching { android.os.Messenger(binder).send(android.os.Message.obtain(null, FlutterPlayerActivity.QUIT)) }
        }
    }

    fun launch(timeoutMs: Long = 20_000): Started {
        val context = appContext() ?: throw IOException("No Android context to start the Flutter player from.")
        val answer = CompletableFuture<Started>()
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val uri = resultData?.getString(FlutterPlayerActivity.EXTRA_URI)
                if (resultCode == 0 && uri != null) answer.complete(Started(uri, resultData.getString(FlutterPlayerActivity.EXTRA_ISOLATE), resultData.getBinder(FlutterPlayerActivity.EXTRA_CONTROL)))
                else answer.completeExceptionally(IOException("The Flutter player started without a VM service."))
            }
        }
        val intent = Intent()
            .setClassName(PACKAGE, ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(FlutterPlayerActivity.EXTRA_RECEIVER, receiver)
        // Android 14 and later tell the player who is starting it only when the caller agrees to say.
        val options = if (android.os.Build.VERSION.SDK_INT >= 34) {
            android.app.ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        } else null
        context.startActivity(intent, options)
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
    internal fun appContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context?
    }.getOrNull()
}

/**
 * `flutter run` for the device itself: compile the app, run it in the player, and keep it hot-reloading.
 *
 * The run lasts as long as the app: every save of a `.dart` file in the package hot-reloads it (state kept),
 * a pubspec change gets `pub get` and a hot restart, and the console takes `r` (reload), `R` (restart) and `q`
 * (quit), as `flutter run` does. What the app prints shows in the console. Stop, `q` or closing the app ends it.
 *
 * With [preview], what runs is not the app but a gallery of its `@Preview` widgets ([DartPreviews]), rewritten
 * before every reload so a preview added, removed or changed shows on the next save.
 */
internal class FlutterRunTask(
    override val name: TaskName,
    private val pkg: Path,
    private val sdk: DartSdk,
    private val flutter: FlutterSdk,
    private val pub: Pub,
    private val console: RunConsole,
    private val preview: Boolean = false,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    private lateinit var out: (String) -> Unit
    private var packageName = ""
    private val galleryFile: Path get() = pkg.resolve(".dart_tool/codeassist/preview/main.dart")

    override suspend fun execute(ctx: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        val io: ProgramIo? = console.io
        out = io?.let { c -> { line: String -> c.stdout(line + "\n") } } ?: ctx.logger()
        io?.started()
        val result = try {
            session(io)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything else ends the run with its reason in the console, never silently.
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
        packageName = name
        val entry = if (preview) {
            writeGallery()
            galleryFile.toUri().toString()
        } else {
            if (!Files.isRegularFile(pkg.resolve("lib/main.dart"))) return fail("A Flutter app needs lib/main.dart.")
            "package:$name/main.dart"
        }
        if (DartPackages.needsPubGet(pkg) && !pubGet()) return fail("pub get failed")

        val app = FlutterSession(
            pkg, sdk, flutter, entry, pkg.resolve(".dart_tool/codeassist/flutter"), out,
            alsoChanged = { if (preview && writeGallery()) listOf(galleryFile) else emptyList() },
        )
        var player: FlutterPlayer.Started? = null
        try {
            out("Compiling $entry ...")
            val started = System.nanoTime()
            val first = app.compile()
            first.messages.forEach(out)
            if (!first.ok) return fail("The app did not compile (${first.errors} error(s)).")
            out("Compiled in ${ms(started)} ms.")

            out("Starting the Flutter player ...")
            val launched = System.nanoTime()
            player = FlutterPlayer.launch()
            val vm = FlutterSession.connect(player.uri)
            vm.onEvent = { stream, event -> if (stream == "Stdout" || stream == "Stderr") printed(event) }
            for (stream in listOf("Stdout", "Stderr")) runCatching { vm.call("streamListen", mapOf("streamId" to stream)) }
            out("The player answered in ${ms(launched)} ms.")
            val uploaded = System.nanoTime()
            app.runIn(vm, player.isolate, first.dill!!)
            out("Running in ${ms(uploaded)} ms. Saving a file hot-reloads it; type r (reload), R (restart) or q (quit).")

            val commands = LinkedBlockingQueue<String>()
            io?.stdin?.let { readCommands(it, commands) }
            var seen = FlutterSession.snapshot(pkg)
            while (currentCoroutineContext().isActive && !app.engineGone) {
                delay(POLL_MS)
                when (commands.poll()?.trim()) {
                    "r" -> app.reload(changedSince(seen).also { seen = FlutterSession.snapshot(pkg) })
                    "R" -> app.restart(changedSince(seen).also { seen = FlutterSession.snapshot(pkg) })
                    "q" -> break
                    else -> {
                        val now = FlutterSession.snapshot(pkg)
                        if (now == seen) continue
                        val changed = FlutterSession.changed(seen, now)
                        seen = now
                        if (changed.any { it.fileName.toString() == DartPackages.PUBSPEC }) {
                            out("pubspec.yaml changed: pub get and a hot restart.")
                            if (pubGet()) { app.uploadAssets(); app.restart(emptyList()) }
                        } else {
                            app.reload(changed.filter { it.toString().endsWith(".dart") && Files.isRegularFile(it) })
                        }
                    }
                }
            }
            if (app.engineGone) out("The app was closed.")
            return TaskResult.Success
        } finally {
            // A run that stopped should not leave its player behind.
            player?.quit()
            app.close()
        }
    }

    /** Rewrite the preview gallery from the app's previews as they are now. Answers whether its text changed. */
    private fun writeGallery(): Boolean {
        val previews = DartPreviews.find(pkg, packageName)
        val changed = DartPreviews.write(galleryFile, DartPreviews.gallery(previews))
        if (changed) out("${previews.size} preview(s): ${previews.joinToString(", ") { it.label }.ifEmpty { "none yet" }}")
        return changed
    }

    private fun pubGet(): Boolean {
        out("> dart pub get")
        return pub.run(pkg, "get", out = out) == 0
    }

    private fun fail(message: String): TaskResult {
        out(message)
        return TaskResult.Failed(message)
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

    private fun changedSince(before: Map<Path, Long>): List<Path> =
        FlutterSession.changed(before, FlutterSession.snapshot(pkg)).filter { it.toString().endsWith(".dart") && Files.isRegularFile(it) }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    private companion object {
        const val POLL_MS = 400L
    }
}
