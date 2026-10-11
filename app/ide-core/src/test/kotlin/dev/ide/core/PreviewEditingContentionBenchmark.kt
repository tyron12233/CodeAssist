package dev.ide.core

import dev.ide.model.LanguageLevel
import dev.ide.model.template.TemplateArgs
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.AnalysisPreempted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Editing cost of an open Compose preview (opt-in:
 * `./gradlew :ide-core:regressionTest --tests '*PreviewEditingContentionBenchmark*'`).
 *
 * Replays the same typing twice through [IdeServicesBackend]: once with only the editor (daemon passes +
 * completion, as [EditorContentionBenchmark]), once with the preview pane's loop added on top, modelled on
 * `ComposePreviewPane` + `AndroidComposePreviewHost`:
 *  - every keystroke asks `isLiveLiteralEdit` (timed: the work it does, now off the UI thread);
 *  - 400ms after the last keystroke the buffer becomes `renderText`, and a lowering pass for it starts on the
 *    preview lane; the next keystroke that moves the buffer past `renderText` cancels that pass, as the host
 *    does through `LocalPreviewBufferStale`.
 *
 * Reported: completion latency, daemon pass-run latency, the caller-thread cost of the literal check, and how
 * many lowerings ran (and finished) against a buffer the editor had already moved past.
 */
@Tag("regression")
class PreviewEditingContentionBenchmark {

    @Test
    fun editingWithPreviewOpen() {
        withTempDir("ide-preview-contention-bench") { dir ->
            IdeServices.createProjectAt(
                dir, "kotlin-console", mapOf(TemplateArgs.NAME to "bench"),
                IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17,
            ).use { ide ->
                runBlocking {
                    withTimeoutOrNull(5_000) { ide.indexStatus.first { it.building } }
                    withTimeoutOrNull(180_000) { ide.indexStatus.first { !it.building } }
                }
                val backend = IdeServicesBackend(initial = ide)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    run(ide, backend, scope)
                } finally {
                    scope.cancel()
                    backend.close()
                }
            }
        }
    }

    private fun run(ide: IdeServices, backend: IdeServicesBackend, scope: CoroutineScope) {
        val module = ide.modules().first()
        val file = ide.sourceRoots(module).first().resolve("Screen.kt")
        val path = file.toString()
        val body = buildString {
            append("package bench\n\n")
            append("data class Model(val a: Int, val b: String, val c: Float)\n\n")
            repeat(FUNCTIONS) { i ->
                append("fun f$i(xs: List<Int>): Int {\n")
                append("    val doubled = xs.map { it * 2 }.filter { it > $i }\n")
                append("    val names = doubled.map { n -> \"v\$n\" }.joinToString(\", \")\n")
                append("    println(names.length + doubled.sum())\n")
                append("    return doubled.fold(0) { acc, v -> acc + v }\n")
                append("}\n\n")
            }
            append("fun Entry() {\n    val m = Model(1, \"x\", 1.0f)\n    println(f3(listOf(m.a)) + f7(listOf(m.a)))\n}\n\n")
        }
        // Inside a body (the per-function lowering cache reuses every sibling) and at top level (moves the
        // file's signature hash, so every lowering is a full re-lower of the file).
        val sites = listOf(
            Triple("in a function body", "fun typing(numbers: List<Int>) {\n    val total = ", "\n}\n") to
                "numbers.filterIndexed { i, v -> i > v }.sum()",
            Triple("at top level", "fun typing(numbers: List<Int>) = numbers.size\n", "\n") to
                "fun extra(k: Int): Int = k * 2 + f1(listOf(k))",
        )
        for ((site, typed) in sites) {
            val (label, head, tail) = site
            // Alternate so neither mode always gets the colder caches; report the second pair.
            measure(backend, scope, path, file, body + head, tail, typed, preview = false)
            measure(backend, scope, path, file, body + head, tail, typed, preview = true)
            val off = measure(backend, scope, path, file, body + head, tail, typed, preview = false)
            val on = measure(backend, scope, path, file, body + head, tail, typed, preview = true)
            report(label, off, on)
        }
    }

    private class Run(
        val completion: List<Long>,
        val daemonRuns: List<Long>,
        val literalCheckUs: List<Long>,
        val lowerings: List<Long>,
        val loweringsStarted: Int,
        val staleFinished: Int,
        val cancelled: Int,
        val wallMs: Long,
    )

    private fun measure(
        backend: IdeServicesBackend, scope: CoroutineScope, path: String, file: java.nio.file.Path,
        head: String, tail: String, typed: String, preview: Boolean,
    ): Run {
        Files.writeString(file, head + tail)
        val completion = ArrayList<Long>()
        val daemonRuns = Collections.synchronizedList(ArrayList<Long>())
        val literalUs = ArrayList<Long>()
        val lowerings = Collections.synchronizedList(ArrayList<Long>())
        val started = AtomicInteger()
        val stale = AtomicInteger()
        val cancelled = AtomicInteger()
        val current = java.util.concurrent.atomic.AtomicReference(head + tail)
        var daemon: Job? = null
        var debounce: Job? = null
        var lowering: Job? = null
        var renderText: String? = null

        fun restartDaemon(text: String) {
            daemon?.cancel()
            daemon = scope.launch {
                delay(DAEMON_DEBOUNCE_MS)
                val t0 = System.nanoTime()
                for (pass in 0 until 4) {
                    try {
                        when (pass) {
                            0 -> backend.editor.codeFolds(path, text)
                            1 -> backend.editor.semanticTokens(path, text)
                            2 -> backend.editor.analyze(path, text)
                            else -> backend.editor.hintsAt(path, text, 0, text.length)
                        }
                    } catch (_: AnalysisPreempted) {
                    }
                }
                daemonRuns += (System.nanoTime() - t0) / 1_000_000
            }
        }

        // The pane: debounce into renderText; a new renderText restarts the host's lowering pass.
        fun previewEdit(text: String) {
            // The host stands down once the buffer moves past the copy it is lowering (LocalPreviewBufferStale).
            if (renderText != null && renderText != text) lowering?.cancel()
            val t0 = System.nanoTime()
            val literal = runCatching { runBlocking { backend.preview.isLiveLiteralEdit(path, text) } }.getOrDefault(false)
            literalUs += (System.nanoTime() - t0) / 1_000
            debounce?.cancel()
            debounce = scope.launch {
                if (!literal) delay(PREVIEW_DEBOUNCE_MS)
                if (renderText == text) return@launch
                renderText = text
                lowering?.cancel()
                lowering = scope.launch {
                    started.incrementAndGet()
                    val l0 = System.nanoTime()
                    try {
                        backend.lowerComposePreview(path, "Entry", 0, text)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        cancelled.incrementAndGet()
                        throw e
                    } catch (_: Throwable) {
                    }
                    lowerings += (System.nanoTime() - l0) / 1_000_000
                    if (text != current.get()) stale.incrementAndGet()
                }
            }
        }

        // Warm the analyzer, JIT and (for the preview run) the lowering cache on the untouched file.
        runBlocking {
            val warm = head + tail
            repeat(2) {
                backend.editor.analyze(path, warm)
                backend.editor.semanticTokens(path, warm)
                backend.editor.complete(path, warm, head.length)
                if (preview) backend.lowerComposePreview(path, "Entry", 0, warm)
            }
        }

        val w0 = System.nanoTime()
        runBlocking {
            repeat(ROUNDS) {
                for (n in 0..typed.length) {
                    val text = head + typed.substring(0, n) + tail
                    current.set(text)
                    restartDaemon(text)
                    if (preview) previewEdit(text)
                    val t0 = System.nanoTime()
                    try {
                        backend.editor.complete(path, text, head.length + n)
                    } catch (_: AnalysisPreempted) {
                    }
                    completion += (System.nanoTime() - t0) / 1_000_000
                    // Bursts of keystrokes with a thinking pause every few: long enough for the daemon AND the
                    // preview debounce to fire, so the next burst lands on their work.
                    delay(if (n % 5 == 4) PAUSE_MS else KEYSTROKE_MS)
                }
            }
            // Let the last passes finish so their latencies count.
            delay(1_500)
            daemon?.cancel(); debounce?.cancel(); lowering?.cancel()
        }
        return Run(
            completion, daemonRuns.toList(), literalUs, lowerings.toList(), started.get(), stale.get(), cancelled.get(),
            (System.nanoTime() - w0) / 1_000_000,
        )
    }

    private fun report(label: String, off: Run, on: Run) {
        fun pct(xs: List<Long>, p: Double): Long = xs.sorted().let { if (it.isEmpty()) -1 else it[((it.size - 1) * p).toInt()] }
        fun line(name: String, xs: List<Long>, unit: String = "ms") =
            "$name n=${xs.size} p50=${pct(xs, .5)}$unit p90=${pct(xs, .9)}$unit p95=${pct(xs, .95)}$unit max=${xs.maxOrNull()}$unit"
        println(
            "\n=== Editing with the Compose preview open: $label ===\n" +
                "-- preview CLOSED\n" +
                line("completion  ", off.completion) + "\n" +
                line("daemon run  ", off.daemonRuns) + "\n" +
                "-- preview OPEN (live)\n" +
                line("completion  ", on.completion) + "\n" +
                line("daemon run  ", on.daemonRuns) + "\n" +
                line("literal chk ", on.literalCheckUs, "us") + "   (runs off the UI thread)\n" +
                line("lowering (completed)", on.lowerings) + "\n" +
                "lowerings started=${on.loweringsStarted} completed=${on.lowerings.size} " +
                "completed-on-stale-text=${on.staleFinished} stood-down=${on.cancelled}\n",
        )
        assertTrue(off.completion.isNotEmpty() && on.completion.isNotEmpty())
    }

    private companion object {
        const val FUNCTIONS = 600
        const val ROUNDS = 3
        const val DAEMON_DEBOUNCE_MS = 300L
        const val PREVIEW_DEBOUNCE_MS = 400L
        const val KEYSTROKE_MS = 120L
        const val PAUSE_MS = 650L
    }
}
