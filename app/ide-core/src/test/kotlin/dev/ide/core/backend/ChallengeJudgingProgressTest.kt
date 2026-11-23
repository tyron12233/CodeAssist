package dev.ide.core.backend

import dev.ide.core.IdeServicesBackend
import dev.ide.store.DailyChallengeService
import dev.ide.store.DailyJudgeEvent
import dev.ide.store.DailyLanguage
import dev.ide.store.DailyVerdict
import dev.ide.store.StoreResult
import dev.ide.ui.backend.UiChallengeLanguage
import dev.ide.ui.backend.UiJudgeProgress
import dev.ide.ui.backend.UiJudgeStep
import dev.ide.ui.backend.UiJudgeStepState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The judge's step reports, folded into [dev.ide.ui.backend.ChallengeService.judging] for the progress sheet.
 */
class ChallengeJudgingProgressTest {

    @Test
    fun stepsFoldIntoProgressWhileTheJudgeWorks() = runBlocking {
        val seen = mutableListOf<UiJudgeProgress?>()
        lateinit var backend: ChallengeBackend
        val judge = object : DailyChallengeService {
            override fun submit(
                date: String,
                language: DailyLanguage,
                source: String,
                onEvent: (DailyJudgeEvent) -> Unit,
            ): StoreResult<DailyVerdict> {
                fun e(step: String, state: String, ms: Long? = null, vararg d: Pair<String, String>) {
                    onEvent(DailyJudgeEvent(step, state, ms, d.toMap()))
                    seen += backend.judging.value
                }
                e("claim", "start"); e("claim", "done", 3, "attempt" to "1")
                e("compile", "start", null, "language" to "kotlin")
                e("compile", "done", 6875, "compilerMs" to "6418")
                e("tests", "start", null, "total" to "13")
                e("tests", "progress", null, "jvmReadyMs" to "706")
                e("tests", "done", 1119, "passed" to "13", "total" to "13")
                e("measure", "start", null, "sizes" to "3", "counted" to "true")
                e("measure", "progress", null, "n" to "1000", "ops" to "320561", "index" to "1", "of" to "3")
                e("measure", "progress", null, "n" to "2000", "ops" to "641918", "index" to "2", "of" to "3")
                e("nonsense", "start")
                return StoreResult.Ok(DailyVerdict(verdict = "accepted", testsPassed = 13, testsTotal = 13))
            }
        }
        backend = ChallengeBackend(IdeServicesBackend(), judge)
        assertNull(backend.judging.value, "nothing is being judged before the first submission")

        val verdict = backend.submit("2026-12-01", UiChallengeLanguage.Kotlin, "fun f() = 1")
        assertTrue(verdict.accepted)

        // Mid-compile, the compile step runs and the rest wait.
        val compiling = seen[2]!!
        assertTrue(compiling.live)
        assertEquals(UiJudgeStepState.Done, compiling.of(UiJudgeStep.Claim).state)
        assertEquals(UiJudgeStepState.Running, compiling.of(UiJudgeStep.Compile).state)
        assertNotNull(compiling.of(UiJudgeStep.Compile).startedAtMs)
        assertEquals(UiJudgeStepState.Waiting, compiling.of(UiJudgeStep.Tests).state)

        // A progress report adds to what the step already knows.
        val testing = seen[5]!!.of(UiJudgeStep.Tests)
        assertEquals(mapOf("total" to "13", "jvmReadyMs" to "706"), testing.detail)

        // Every measured size is kept, not just the latest.
        val measuring = seen[9]!!.of(UiJudgeStep.Measure)
        assertEquals("320561,641918", measuring.detail["opsSeries"])
        assertEquals("2000", measuring.detail["n"])

        // An unknown step changes nothing.
        assertEquals(seen[9], seen[10])

        // After the verdict: finished, the step it was in is over, and steps never reached still wait.
        val after = backend.judging.value!!
        assertTrue(after.finished)
        assertEquals(UiJudgeStepState.Done, after.of(UiJudgeStep.Measure).state)
        assertEquals(UiJudgeStepState.Waiting, after.of(UiJudgeStep.Record).state)
        assertEquals(6875L, after.of(UiJudgeStep.Compile).ms)
        assertEquals(UiChallengeLanguage.Kotlin, after.language)
    }

    @Test
    fun aJudgeThatCannotStreamLeavesTheProgressNotLive() = runBlocking {
        val judge = object : DailyChallengeService {
            override fun submit(date: String, language: DailyLanguage, source: String): StoreResult<DailyVerdict> =
                StoreResult.Ok(DailyVerdict(verdict = "wrong_answer"))
        }
        val backend = ChallengeBackend(IdeServicesBackend(), judge)
        backend.submit("2026-12-01", UiChallengeLanguage.Java, "class Solution {}")
        val after = backend.judging.value!!
        assertTrue(after.finished)
        assertEquals(false, after.live, "no step was reported, so the sheet must not pretend to know them")
    }
}
