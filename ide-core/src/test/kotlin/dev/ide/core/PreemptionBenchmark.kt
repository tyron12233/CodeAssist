package dev.ide.core

import dev.ide.bench.Bench
import dev.ide.bench.Direction
import dev.ide.bench.MetricUnit
import dev.ide.bench.RegressionSuite
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Preemption / head-of-line-stall benchmark (opt-in: `./gradlew :ide-core:regressionTest`). The editor runs
 * completion, analysis, and preview over ONE serialized engine worker (`engineDispatcher`,
 * limitedParallelism(1)); a completion request preempts an in-flight background analysis by flipping its
 * cancel token, but the analysis only actually yields the worker at its next `EngineCancellation.checkCanceled()`
 * poll. This measures the gap between the two: how much slower a completion is when fired while a heavy analysis
 * is mid-flight, versus the engine idle. That delta is the *felt* typing latency the warm micro-benchmarks
 * (which run on an idle engine) don't capture.
 *
 * The regression signal is the **stall ratio** (under-analysis / idle): if preemption works it stays low;
 * if a `checkCanceled()` poll is ever removed from the analyzer, a completion would wait for the whole analysis
 * and the ratio (and the absolute under-analysis latency) would blow up. Wall-clock under contention is noisy,
 * so the gates are loose drift + an absolute ceiling.
 *
 * Baselines: `baselines/preemption.json` (seeded on first run — commit it).
 */
@Tag("regression")
class PreemptionBenchmark {

    @Test
    fun completionLatencyIdleVsUnderBackgroundAnalysis() {
        val dir = Files.createTempDirectory("ide-preempt-bench")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val backend = IdeServicesBackend(initial = ide)
            try {
                val app = ide.modules().first { it.name == "app" }
                val srcRoot = ide.sourceRoots(app).first()

                // Completion fixture: a member access on a Formatter in Main.java (a resolved-binding member case).
                val mainFile = srcRoot.resolve("com/example/app/Main.java")
                val original = Files.readString(mainFile)
                val completionText = original.replace(
                    "Formatter formatter = new Formatter();",
                    "Formatter formatter = new Formatter();\n        formatter.\n",
                )
                val completionOffset = completionText.indexOf("formatter.\n") + "formatter.".length
                val mainPath = mainFile.toString()
                fun complete() = runBlocking { backend.editor.complete(mainPath, completionText, completionOffset) }.items.size.toLong()

                // Heavy analysis fixture: a large synthetic class in the same module, so a single analyze takes
                // long enough to reliably be in flight when a completion fires.
                val hugePath = srcRoot.resolve("com/example/app/Huge.java").toString()
                val hugeText = buildString {
                    append("package com.example.app;\nclass Huge {\n")
                    repeat(1200) { i ->
                        append("  int m$i(int a, int b) { int x = a + b; java.util.List<String> l = new java.util.ArrayList<>(); l.add(\"\" + x); return l.size() + x; }\n")
                    }
                    append("}\n")
                }
                fun analyzeOnce() = runCatching { runBlocking { backend.editor.analyze(hugePath, hugeText) } }

                // Warm up both paths (JDT env cache, index, JIT).
                repeat(3) { complete() }
                analyzeOnce()
                val analyzeMs = run { val t = System.nanoTime(); analyzeOnce(); (System.nanoTime() - t) / 1_000_000 }

                val suite = RegressionSuite("preemption")

                // 1) Completion latency with the engine idle.
                val idleNs = Bench.nsPerOp(warmup = 2, runs = 5, ops = 3) { complete() }

                // 2) Completion latency while a background analyze loops continuously (always one in flight).
                val stop = AtomicBoolean(false)
                val churn = Thread {
                    while (!stop.get()) analyzeOnce()
                }.apply { isDaemon = true; start() }
                Thread.sleep(20) // let the first background analyze get onto the engine worker
                val underNs = try {
                    Bench.nsPerOp(warmup = 2, runs = 5, ops = 3) { complete() }
                } finally {
                    stop.set(true); churn.join(10_000)
                }

                val ratio = if (idleNs > 0) underNs / idleNs else 1.0
                println(
                    "\n=== Preemption: completion latency idle vs under background analysis ===\n" +
                        "one analyze ≈ ${analyzeMs}ms\n" +
                        "idle:           ${Bench.ns(idleNs)}\n" +
                        "under-analysis: ${Bench.ns(underNs)}\n" +
                        "stall ratio:    ${"%.2f".format(ratio)}x\n",
                )

                suite.latencyNs("latency.complete-idle.ns", idleNs, tolerance = 1.5, ceilingNs = 300_000_000.0)
                // The load-bearing gate: a completion must not wait for the whole analysis. Loose drift (noisy
                // under contention) + an absolute ceiling well under a single analyze, so a lost checkCanceled
                // poll (completion blocks for the full analyze) fails the suite.
                suite.latencyNs("latency.complete-under-analysis.ns", underNs, tolerance = 2.0, ceilingNs = 250_000_000.0)
                suite.metric("ratio.stall", ratio, Direction.LOWER_BETTER, MetricUnit.RATIO, tolerance = 2.0, bound = 25.0)
                suite.finishAndAssert()
                assertTrue(Bench.sink >= 0L || Bench.sink < 0L)
            } finally {
                backend.close()
            }
        }
        dir.toFile().deleteRecursively()
    }
}
