package dev.codeassist.swift

import dev.ide.platform.log.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * A SwiftUI preview of one file: the engine running the file's previews in the compiler's JIT, kept alive between
 * renders so taps and edits are answered at once, and restarted (compiling afresh) when the file's text changes.
 *
 * The engine asks for text to be measured as it lays out ([SwiftTextEngine], so layout uses exactly the widths that are
 * drawn) and sends each render as a frame ([SwiftPreviewFrame]).
 */
class SwiftPreviewSession(
    private val file: Path,
    private val toolchain: SwiftToolchain,
    private val textEngine: SwiftTextEngine,
    private val log: Logger,
) {
    data class State(
        val frames: List<SwiftPreviewFrame> = emptyList(),
        val problems: List<String> = emptyList(),
        /** Compiling or starting: the last frames (if any) are still shown, marked as stale. */
        val busy: Boolean = false,
        val message: String? = null,
        val previewCount: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var generation = 0
    private var lastText: String? = null
    private var dark = false
    @Volatile private var framesSeen = 0

    /** Show [text] (the editor's live buffer for [file]): compile and start it when it changed. */
    @Synchronized
    fun update(text: String, dark: Boolean) {
        if (dark != this.dark) {
            this.dark = dark
            send("scheme ${if (dark) "dark" else "light"}")
        }
        if (text == lastText && process?.isAlive == true) return
        lastText = text
        restart(text)
    }

    @Synchronized
    fun tap(id: Int) = send("tap $id")

    @Synchronized
    fun set(id: Int, value: String) = send("set $id ${JSONObject.quote(value)}")

    @Synchronized
    fun close() {
        generation++
        stop()
    }

    private fun send(line: String) {
        val out = stdin ?: return
        try {
            out.write(line)
            out.write("\n")
            out.flush()
        } catch (_: IOException) {
            // The engine exited; its stderr says why.
        }
    }

    private fun stop() {
        runCatching { stdin?.write("quit\n"); stdin?.flush() }
        process?.destroyForcibly()
        process = null
        stdin = null
    }

    private fun restart(text: String) {
        stop()
        val gen = ++generation
        if (!SwiftPreviewSource.hasPreviews(text)) {
            _state.value = State(message = "Add a #Preview to see it here:\n\n#Preview {\n    ContentView()\n}")
            return
        }
        _state.value = _state.value.copy(busy = true, message = null)
        Thread({
            // A plugin thread must never take the IDE down: whatever goes wrong is shown in the pane.
            try {
                launch(gen, text)
            } catch (t: Throwable) {
                log.warn("SwiftUI preview of ${file.fileName} failed", t)
                publish(gen) { State(message = "The preview failed: ${t.message ?: t.javaClass.simpleName}") }
            }
        }, "swiftui-preview").apply { isDaemon = true }.start()
    }

    private fun launch(gen: Int, text: String) {
        val status = toolchain.prepare()
        if (status is SwiftToolchain.Status.Unavailable) return publish(gen) { State(message = status.reason) }
        if (!toolchain.hasSwiftUI) return publish(gen) { State(message = "This build of the Swift plugin carries no SwiftUI engine.") }

        val outDir = SwiftPreviewSource.packageDir(file)?.resolve(SwiftPackages.BUILD_DIR)?.resolve("preview")
            ?.resolve(SwiftPreviewSource.targetDir(file)?.fileName?.toString() ?: "file")
            ?: toolchain.home.resolve("preview").resolve(Integer.toHexString(file.toString().hashCode()))
        val build = try {
            SwiftPreviewSource.prepare(file, text, outDir)
        } catch (e: IOException) {
            return publish(gen) { State(message = "Could not prepare the preview: ${e.message}") }
        }
        val command = toolchain.interpretCommand(build.files, "PreviewApp", withSwiftUI = true)
        val proc = try {
            ProcessBuilder(command).directory(outDir.toFile()).apply { environment().putAll(toolchain.environment()) }.start()
        } catch (e: IOException) {
            return publish(gen) { State(message = "Could not start the preview: ${e.message}") }
        }
        synchronized(this) {
            if (gen != generation) { proc.destroyForcibly(); return }
            process = proc
            stdin = proc.outputStream.bufferedWriter()
            if (dark) send("scheme dark")
        }
        val started = System.nanoTime()

        // stderr: the compiler's messages, and a crash's report.
        val errors = ArrayList<String>()
        val stderr = Thread({
            try {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(errors) { errors += line }
                }
            } catch (_: IOException) {
                // The engine was stopped (an edit restarted it, or the pane closed): its stream closed under us.
            }
        }, "swiftui-preview-stderr").apply { isDaemon = true; start() }

        framesSeen = 0
        try {
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (gen != generation) return@forEachLine
                try {
                    handle(line, gen, build.previewCount, started)
                } catch (e: Exception) {
                    log.warn("unreadable line from the SwiftUI engine: ${line.take(200)}", e)
                }
            }
        } catch (_: IOException) {
            // Stopped.
        }
        finish(gen, proc, stderr, errors, build)
    }

    private fun handle(line: String, gen: Int, previewCount: Int, started: Long) {
                when {
                    line.startsWith("{\"t\":\"measure\"") -> answerMeasure(JSONObject(line))
                    line.startsWith("{\"t\":\"frame\"") -> {
                        val previews = JSONObject(line).getJSONArray("previews")
                        val parsed = (0 until previews.length()).map { SwiftPreviewFrame.parse(previews.getJSONObject(it)) }
                        if (framesSeen++ == 0) log.info("preview of ${file.fileName}: first frame in ${(System.nanoTime() - started) / 1_000_000} ms")
                        publish(gen) { State(frames = parsed, previewCount = previewCount) }
                    }
                }
    }

    private fun finish(gen: Int, proc: Process, stderr: Thread, errors: List<String>, build: SwiftPreviewSource.Build) {
        stderr.join(2000)
        val code = runCatching { proc.waitFor() }.getOrDefault(-1)
        if (gen != generation) return
        val problems = diagnostics(synchronized(errors) { errors.toList() }, build.origins)
        publish(gen) { previous ->
            when {
                problems.isNotEmpty() -> previous.copy(problems = problems, busy = false)
                code != 0 -> previous.copy(busy = false, problems = listOf("The preview stopped (exit code $code). " +
                    synchronized(errors) { errors.takeLast(6).joinToString(" ") }))
                else -> previous.copy(busy = false)
            }
        }
    }

    private fun publish(gen: Int, next: (State) -> State) {
        if (gen != generation) return
        _state.value = next(_state.value)
    }

    private fun answerMeasure(o: JSONObject) {
        val spans = SwiftPreviewFrame.spans(o.getJSONArray("sp"))
        val width = if (o.isNull("w")) null else o.getDouble("w").toFloat()
        val lines = if (o.isNull("n")) null else o.getInt("n")
        val advance = if (o.isNull("lh")) null else o.getDouble("lh").toFloat()
        val m = textEngine.measure(spans, width, lines, advance, o.optString("a", "leading"))
        synchronized(this) { send("m ${o.getInt("id")} ${m.width} ${m.height} ${m.firstBaseline} ${m.lastBaseline}") }
    }

    /** The compiler's errors, on the user's files (the generated copies keep every line's number). */
    private fun diagnostics(lines: List<String>, origins: Map<Path, Path>): List<String> {
        val pattern = Regex("""^(.+?):(\d+):(\d+): error: (.*)$""")
        return lines.mapNotNull { line ->
            val m = pattern.find(line) ?: return@mapNotNull null
            val path = runCatching { Paths.get(m.groupValues[1]) }.getOrNull()
            val origin = path?.let { origins[it] ?: origins.entries.firstOrNull { e -> e.key.fileName == it.fileName }?.value }
            val where = origin?.fileName?.toString() ?: path?.fileName?.toString() ?: m.groupValues[1]
            "$where:${m.groupValues[2]}: ${m.groupValues[4]}"
        }.distinct()
    }
}

/** One session per previewed file, shared by the panes showing it. */
object SwiftPreviewSessions {
    private class Entry(val session: SwiftPreviewSession, var users: Int)
    private val sessions = ConcurrentHashMap<Path, Entry>()

    fun acquire(file: Path, tools: SwiftPlugin.Tools): SwiftPreviewSession = synchronized(this) {
        val entry = sessions.getOrPut(file) { Entry(SwiftPreviewSession(file, tools.toolchain, tools.text, tools.log), 0) }
        entry.users++
        entry.session
    }

    /**
     * Let go of [session]; the last user's release stops it after [LINGER_MS], so the pane being recreated (switching
     * between Preview and Split, rotating) picks the running preview up again instead of compiling it afresh.
     */
    fun release(session: SwiftPreviewSession) {
        synchronized(this) {
            val entry = sessions.values.firstOrNull { it.session === session } ?: return
            entry.users--
        }
        timer.schedule(object : java.util.TimerTask() {
            override fun run() = synchronized(this@SwiftPreviewSessions) {
                val (file, entry) = sessions.entries.firstOrNull { it.value.session === session } ?: return
                if (entry.users <= 0) {
                    sessions.remove(file)
                    session.close()
                }
            }
        }, LINGER_MS)
    }

    private const val LINGER_MS = 10_000L
    private val timer = java.util.Timer("swiftui-preview-linger", true)
}

/** Which files the SwiftUI pane is for: Swift files that declare a preview. Cheap, cached by modification time. */
object SwiftPreviewFiles {
    private data class Answer(val stamp: Long, val size: Long, val applies: Boolean)
    private val answers = ConcurrentHashMap<String, Answer>()

    fun appliesTo(path: String): Boolean {
        if (!path.endsWith(".swift")) return false
        val file = Paths.get(path)
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return false
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        answers[path]?.let { if (it.stamp == stamp && it.size == size) return it.applies }
        val applies = runCatching { SwiftPreviewSource.hasPreviews(String(Files.readAllBytes(file))) }.getOrDefault(false)
        answers[path] = Answer(stamp, size, applies)
        return applies
    }
}
