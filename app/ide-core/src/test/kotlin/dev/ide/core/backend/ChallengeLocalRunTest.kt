package dev.ide.core.backend

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiChallengeLanguage
import dev.ide.ui.backend.UiChallengeMaterial
import dev.ide.ui.backend.UiChallengeProblem
import dev.ide.ui.backend.UiChallengeSample
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Run examples" ([dev.ide.ui.backend.ChallengeService.run]) after the challenge editor has already looked
 * at the solver's code.
 *
 * The two halves of the player share one file in the scratch project: the editor completes and analyses the
 * solver's snippet at `Solution.kt`, and the runner writes the harness (the program that calls it, and the
 * only thing here with a `main`) to that same path. Analysis leaves the snippet in the engine as an open
 * buffer, and a run flushes every open buffer to disk before it compiles — so a run that does not move the
 * overlay with the file finds the snippet back on disk and fails with "no runnable main() found in app",
 * which is what the player reported as "Did not compile".
 */
class ChallengeLocalRunTest {

    @Test
    fun runsTheHarnessAfterTheEditorHasAnalysedTheSnippet() {
        runBlocking {
            withTempDir("challenge-local-run") { home ->
                val projects = home.resolve("projects")
                Files.createDirectories(projects)
                val manager = ProjectManager.desktop(projects)
                try {
                    val challenges = IdeServicesBackend(manager = manager).challenges
                    // The player prepares the scratch, then analyses on every edit — which is what puts the
                    // snippet into the engine as an open buffer.
                    challenges.prepare(UiChallengeLanguage.Kotlin)
                    challenges.analyze(UiChallengeLanguage.Kotlin, SNIPPET)

                    val run = challenges.run(UiChallengeLanguage.Kotlin, SNIPPET, problem())
                    assertTrue(run.compiled, "the harness should compile and run; diagnostics=${run.diagnostics}")
                    assertEquals(
                        listOf(true), run.outcomes.map { it.passed },
                        "the sample should pass; outcomes=${run.outcomes}, diagnostics=${run.diagnostics}",
                    )
                } finally {
                    manager.dispose()
                }
            }
        }
    }

    private fun problem() = UiChallengeProblem(
        id = "p1",
        slug = "double-it",
        title = "Double It",
        difficulty = "easy",
        statement = "Return twice the number.",
        materials = mapOf(
            UiChallengeLanguage.Kotlin to UiChallengeMaterial(
                language = UiChallengeLanguage.Kotlin,
                stub = SNIPPET,
                harness = HARNESS,
                functionName = "doubleIt",
                returnType = "Int",
                parameters = listOf("n" to "Int"),
            ),
        ),
        samples = listOf(UiChallengeSample(idx = 0, inputJson = "[21]", expectedJson = "42")),
    )

    private companion object {
        const val SNIPPET = "fun doubleIt(n: Int): Int {\n    return n * 2\n}\n"

        /** The shape of a real harness: the solution spliced in, and the entry point the runner launches. */
        val HARNESS = """
            {{USER_CODE}}

            fun main() {
                println("__CA_BEGIN__")
                println("{\"results\":[{\"id\":0,\"v\":" + doubleIt(21) + "}]}")
                println("__CA_END__")
            }
        """.trimIndent()
    }
}
