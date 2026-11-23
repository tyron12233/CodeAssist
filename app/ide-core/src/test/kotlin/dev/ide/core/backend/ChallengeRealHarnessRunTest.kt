package dev.ide.core.backend

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiChallengeLanguage
import dev.ide.ui.backend.UiChallengeMaterial
import dev.ide.ui.backend.UiChallengeProblem
import dev.ide.ui.backend.UiChallengeSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Run examples" with the REAL harness the judge serves (two-sum's, as `judge/src/harness.ts` builds it,
 * kept under test resources), in both languages, through the same sequence the player makes: prepare, analyse
 * the solver's code as they type, then run, and run again.
 *
 * [ChallengeLocalRunTest] guards the overlay bug with a toy harness; this one exists because the toy passed
 * while the app kept failing, so it runs what the app actually runs.
 */
class ChallengeRealHarnessRunTest {

    @Test
    fun kotlinRunsTheRealHarness() {
        check(UiChallengeLanguage.Kotlin, KOTLIN_SOLUTION, "two-sum-harness.kt.txt")
    }

    @Test
    fun javaRunsTheRealHarness() {
        check(UiChallengeLanguage.Java, JAVA_SOLUTION, "two-sum-harness.java.txt")
    }

    /**
     * The phone's editor does not wait for a run: it keeps asking for diagnostics, hints and folds on the
     * solver's snippet while the run is going, and each of those puts the snippet back in the engine's open
     * buffer for the shared file. Runs made while that traffic is going on must still find the harness.
     */
    @Test
    fun runsWhileTheEditorKeepsAnalysing() {
        val harness = javaClass.getResource("/challenge/two-sum-harness.kt.txt")!!.readText()
        val language = UiChallengeLanguage.Kotlin
        runBlocking {
            withTempDir("challenge-real-harness-race") { home ->
                val projects = home.resolve("projects")
                Files.createDirectories(projects)
                val manager = ProjectManager.desktop(projects)
                try {
                    val challenges = IdeServicesBackend(manager = manager).challenges
                    challenges.prepare(language)
                    challenges.analyze(language, KOTLIN_SOLUTION)
                    val problem = problem(language, harness)
                    val editor = launch(Dispatchers.Default) {
                        while (isActive) {
                            challenges.analyze(language, KOTLIN_SOLUTION)
                            challenges.hints(language, KOTLIN_SOLUTION, 0, KOTLIN_SOLUTION.length)
                            challenges.folds(language, KOTLIN_SOLUTION)
                        }
                    }
                    try {
                        for (attempt in 1..3) {
                            val run = challenges.run(language, KOTLIN_SOLUTION, problem)
                            assertTrue(run.compiled, "attempt $attempt should compile and run; diagnostics=${run.diagnostics}")
                            assertEquals(listOf(true, true, true), run.outcomes.map { it.passed }, "attempt $attempt: ${run.outcomes} ${run.diagnostics}")
                        }
                    } finally {
                        editor.cancelAndJoin()
                    }
                } finally {
                    manager.dispose()
                }
            }
        }
    }

    private fun check(language: UiChallengeLanguage, solution: String, harnessResource: String) {
        val harness = javaClass.getResource("/challenge/$harnessResource")!!.readText()
        runBlocking {
            withTempDir("challenge-real-harness") { home ->
                val projects = home.resolve("projects")
                Files.createDirectories(projects)
                val manager = ProjectManager.desktop(projects)
                try {
                    val challenges = IdeServicesBackend(manager = manager).challenges
                    challenges.prepare(language)
                    challenges.analyze(language, solution)
                    val problem = problem(language, harness)
                    for (attempt in 1..2) {
                        val run = challenges.run(language, solution, problem)
                        assertTrue(run.compiled, "attempt $attempt should compile and run; diagnostics=${run.diagnostics}")
                        assertEquals(
                            listOf(true, true, true), run.outcomes.map { it.passed },
                            "attempt $attempt: every sample should pass; outcomes=${run.outcomes}, diagnostics=${run.diagnostics}",
                        )
                        // The player keeps analysing between runs.
                        challenges.analyze(language, solution)
                    }
                } finally {
                    manager.dispose()
                }
            }
        }
    }

    private fun problem(language: UiChallengeLanguage, harness: String) = UiChallengeProblem(
        id = "p1",
        slug = "two-sum",
        title = "Two Sum",
        difficulty = "easy",
        statement = "Find the two indices.",
        materials = mapOf(
            language to UiChallengeMaterial(
                language = language,
                stub = "",
                harness = harness,
                functionName = "twoSum",
                returnType = if (language == UiChallengeLanguage.Kotlin) "IntArray" else "int[]",
                parameters = emptyList(),
            ),
        ),
        samples = listOf(
            UiChallengeSample(idx = 0, inputJson = "[[2,7,11,15],9]", expectedJson = "[0,1]"),
            UiChallengeSample(idx = 1, inputJson = "[[3,2,4],6]", expectedJson = "[1,2]"),
            UiChallengeSample(idx = 2, inputJson = "[[3,3],6]", expectedJson = "[0,1]"),
        ),
    )

    private companion object {
        val KOTLIN_SOLUTION = """
            fun twoSum(nums: IntArray, target: Int): IntArray {
                val seen = HashMap<Int, Int>()
                for (i in nums.indices) {
                    val j = seen[target - nums[i]]
                    if (j != null) return intArrayOf(j, i)
                    seen[nums[i]] = i
                }
                return intArrayOf()
            }
        """.trimIndent()

        val JAVA_SOLUTION = """
            class Solution {
                int[] twoSum(int[] nums, int target) {
                    java.util.Map<Integer, Integer> seen = new java.util.HashMap<Integer, Integer>();
                    for (int i = 0; i < nums.length; i++) {
                        Integer j = seen.get(target - nums[i]);
                        if (j != null) return new int[] { j, i };
                        seen.put(nums[i], i);
                    }
                    return new int[0];
                }
            }
        """.trimIndent()
    }
}
