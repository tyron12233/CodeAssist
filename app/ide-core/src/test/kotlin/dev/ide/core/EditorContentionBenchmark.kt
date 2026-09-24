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
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Completion latency while TYPING, with the editor's highlighting passes competing for the one engine worker
 * (opt-in: `./gradlew :ide-core:regressionTest --tests '*EditorContentionBenchmark*'`).
 *
 * [PreemptionBenchmark] measures one completion against one analysis in flight. What a user feels is a
 * sequence: bursts of keystrokes with a completion after each, and pauses long enough for the daemon to start
 * a full pass run (diagnostics, semantic tokens, folds, inlays over the whole file) that the next burst then
 * supersedes. This replays that shape on a Kotlin file big enough for the passes to take real time, and
 * reports the completion latency distribution (the tail is the point) and how often a result came back
 * incomplete, which is how often the popup has to go back to the engine instead of narrowing locally.
 */
@Tag("regression")
class EditorContentionBenchmark {

    @Test
    fun completionWhileTypingAgainstDaemonPasses() {
        withTempDir("ide-contention-bench") { dir ->
            IdeServices.createProjectAt(
                dir, "kotlin-console", mapOf(TemplateArgs.NAME to "bench"),
                IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17,
            ).use { ide ->
                // Let the index build start, then wait for it to settle, so the run measures the editor and not
                // a cold index.
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
        val srcRoot = ide.sourceRoots(module).first()
        val file = srcRoot.resolve("Heavy.kt")
        val path = file.toString()
        // A file whose passes take real time: many functions, each with lambdas and inferred locals.
        val body = buildString {
            append("package bench\n\n")
            repeat(FUNCTIONS) { i ->
                append("fun f$i(xs: List<Int>): Int {\n")
                append("    val doubled = xs.map { it * 2 }.filter { it > $i }\n")
                append("    val names = doubled.map { n -> \"v\$n\" }.joinToString(\", \")\n")
                append("    println(names.length + doubled.sum())\n")
                append("    return doubled.fold(0) { acc, v -> acc + v }\n")
                append("}\n\n")
            }
        }
        // The typing site: a member access on a List inside a new function at the end, typed one character at
        // a time, like completing `numbers.filterIndexed`.
        val head = body + "fun typing(numbers: List<Int>) {\n    numbers."
        val tail = "\n}\n"
        Files.writeString(file, head + tail)

        val typed = "filterIndexed"
        val samples = ArrayList<Long>()
        var incomplete = 0
        var daemon: Job? = null

        fun restartDaemon(text: String) {
            daemon?.cancel()
            daemon = scope.launch {
                delay(DAEMON_DEBOUNCE_MS)
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
            }
        }

        // Warm the analyzer, the symbol model and the JIT on the untouched file.
        runBlocking {
            val warm = head + tail
            repeat(2) {
                backend.editor.analyze(path, warm)
                backend.editor.semanticTokens(path, warm)
                backend.editor.complete(path, warm, head.length)
            }
        }

        runBlocking {
            repeat(ROUNDS) {
                for (n in 0..typed.length) {
                    val text = head + typed.substring(0, n) + tail
                    val caret = head.length + n
                    restartDaemon(text)
                    val t0 = System.nanoTime()
                    val result = try {
                        backend.editor.complete(path, text, caret)
                    } catch (_: AnalysisPreempted) {
                        null
                    }
                    samples += (System.nanoTime() - t0) / 1_000_000
                    if (result == null || result.isIncomplete) incomplete++
                    // A burst of four keystrokes, then a pause long enough for the daemon to start its run.
                    delay(if (n % 4 == 3) PAUSE_MS else KEYSTROKE_MS)
                }
            }
        }

        val sorted = samples.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        println(
            "\n=== Completion while typing, daemon passes competing ===\n" +
                "completions: ${samples.size}\n" +
                "p50: ${pct(0.50)}ms  p90: ${pct(0.90)}ms  p95: ${pct(0.95)}ms  max: ${sorted.last()}ms\n" +
                "incomplete results (popup must re-query): $incomplete / ${samples.size}\n",
        )
        assertTrue(samples.isNotEmpty())
    }

    private companion object {
        const val FUNCTIONS = 250
        const val ROUNDS = 3
        const val DAEMON_DEBOUNCE_MS = 300L
        const val KEYSTROKE_MS = 120L
        const val PAUSE_MS = 450L
    }
}
