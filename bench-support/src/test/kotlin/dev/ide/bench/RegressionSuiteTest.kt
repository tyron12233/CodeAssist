package dev.ide.bench

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The gate logic: first run seeds, a worse value fails, an improvement and within-tolerance pass. */
class RegressionSuiteTest {

    private fun tmpDir(): Path = Files.createTempDirectory("baseline-test")

    @Test
    fun firstRunSeedsBaselineAndPasses() {
        val dir = tmpDir()
        try {
            val suite = RegressionSuite("latency", dir, update = false)
            suite.latencyNs("member-access.ns", 2_600_000.0)
            suite.quality("overall.mrr", 0.84)
            suite.finishAndAssert() // no baseline yet → seeded, passes

            val file = dir.resolve("latency.json")
            assertTrue(Files.exists(file), "baseline file should be seeded")
            val saved = FlatJson.read(Files.readString(file))
            assertEquals(2_600_000.0, saved["member-access.ns"])
            assertEquals(0.84, saved["overall.mrr"]!!, 1e-9)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun latencyRegressionBeyondToleranceFails() {
        val dir = tmpDir()
        try {
            Files.writeString(dir.resolve("lat.json"), FlatJson.write(mapOf("x.ns" to 1_000_000.0)))
            val suite = RegressionSuite("lat", dir, update = false)
            suite.latencyNs("x.ns", 3_000_000.0, tolerance = 0.5) // 3x baseline, allowed 1.5x → FAIL
            assertFailsWith<AssertionError> { suite.finishAndAssert() }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun qualityDropBelowToleranceFails() {
        val dir = tmpDir()
        try {
            Files.writeString(dir.resolve("q.json"), FlatJson.write(mapOf("recall" to 1.0)))
            val suite = RegressionSuite("q", dir, update = false)
            suite.quality("recall", 0.80, tolerance = 0.05) // dropped to 0.80, floor 0.95 → FAIL
            assertFailsWith<AssertionError> { suite.finishAndAssert() }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun withinTolerancePassesAndDoesNotRewriteBaseline() {
        val dir = tmpDir()
        try {
            val file = dir.resolve("lat.json")
            Files.writeString(file, FlatJson.write(mapOf("x.ns" to 1_000_000.0)))
            val suite = RegressionSuite("lat", dir, update = false)
            suite.latencyNs("x.ns", 1_200_000.0, tolerance = 1.5) // +20%, well within → OK
            suite.finishAndAssert()
            // baseline untouched (no update flag, no new keys)
            assertEquals(1_000_000.0, FlatJson.read(Files.readString(file))["x.ns"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun updateFlagRewritesEvenWithoutRegression() {
        val dir = tmpDir()
        try {
            val file = dir.resolve("lat.json")
            Files.writeString(file, FlatJson.write(mapOf("x.ns" to 1_000_000.0)))
            val suite = RegressionSuite("lat", dir, update = true)
            suite.latencyNs("x.ns", 700_000.0, tolerance = 1.5)
            suite.finishAndAssert()
            assertEquals(700_000.0, FlatJson.read(Files.readString(file))["x.ns"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun absoluteCeilingFailsRegardlessOfBaseline() {
        val dir = tmpDir()
        try {
            Files.writeString(dir.resolve("lat.json"), FlatJson.write(mapOf("x.ns" to 900_000_000.0)))
            val suite = RegressionSuite("lat", dir, update = false)
            // current is below baseline (would pass drift) but over the interactive ceiling → FAIL
            suite.latencyNs("x.ns", 800_000_000.0, tolerance = 1.5, ceilingNs = 500_000_000.0)
            assertFailsWith<AssertionError> { suite.finishAndAssert() }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun scoreMetricsAreCorrect() {
        val m = CompletionScore.metrics(listOf(
            QualityCaseResult("a", "x", rank = 0, candidates = 10),
            QualityCaseResult("b", "x", rank = 2, candidates = 10),
            QualityCaseResult("c", "x", rank = -1, candidates = 10),
            QualityCaseResult("d", "x", rank = 4, candidates = 10),
        ))
        assertEquals(4, m.n)
        assertEquals(0.75, m.recall, 1e-9)                 // 3 of 4 present
        assertEquals(0.25, m.top1, 1e-9)                   // 1 of 4 at rank 0
        assertEquals(0.75, m.top5, 1e-9)                   // ranks 0,2,4 are < 5
        assertEquals((1.0 + 1.0 / 3 + 0.0 + 1.0 / 5) / 4, m.mrr, 1e-9)
    }
}
