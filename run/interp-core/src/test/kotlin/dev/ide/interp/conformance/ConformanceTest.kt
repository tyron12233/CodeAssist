package dev.ide.interp.conformance

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the Kotlin-language conformance corpus (see [ConformanceHarness]) and prints a scorecard of how close
 * the interpreter is to real Kotlin, broken down by language-feature category.
 *
 * This is a **progress instrument, not a gate**: it does not fail the build on a GAP (an unsupported feature
 * the interpreter honestly refuses) — those are the frontier we are tracking. It only asserts the corpus was
 * actually found and run. A FAIL (ran, wrong answer) or ERROR (unexpected crash) is a real defect surfaced in
 * the printed report; promote this test to a hard gate once the corpus is clean by switching the assertions
 * at the bottom on.
 *
 * Run it on its own:  `./gradlew :interp-core:test --tests '*ConformanceTest*' -i`
 */
class ConformanceTest {

    @Test
    fun scorecard() {
        val report = ConformanceHarness.run()
        val text = ConformanceHarness.format(report)
        println(text)

        // Also drop a markdown copy under build/ so the scorecard is easy to diff between runs.
        runCatching {
            val out = Paths.get("build", "reports", "conformance.md")
            Files.createDirectories(out.parent)
            Files.writeString(out, "```\n$text```\n")
        }

        assertTrue(report.total > 0, "no conformance corpus files were discovered")
        // Frontier GAPs (a feature the interpreter honestly refuses) stay informational — they're the roadmap,
        // not a regression. But a FAIL (ran, wrong answer) or an ERROR (unexpected crash) is a real defect, and
        // the corpus is clean of both — so gate on them: a change that introduces one fails the build here.
        assertEquals(0, report.fail, "interpreter produced wrong answers — see scorecard:\n${ConformanceHarness.format(report)}")
        assertEquals(0, report.error, "interpreter crashed unexpectedly — see scorecard:\n${ConformanceHarness.format(report)}")
    }
}
