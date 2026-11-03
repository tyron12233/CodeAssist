package dev.ide.core.backend

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every compiled exercise's own solution, run through the real checker. The gate before a catalog is
 * published: a lesson whose solution fails would tell a learner who got it right that they are wrong.
 *
 * Opt-in (`-Plearn.validateSolutions=true`) because it compiles and runs dozens of programs, and the
 * coroutine lessons resolve kotlinx-coroutines the first time.
 */
class LearnSolutionsTest {

    @Test
    fun everySolutionPassesItsOwnCheck() {
        assumeTrue(System.getProperty("learn.validateSolutions") == "true", "set -Plearn.validateSolutions=true")
        runBlocking {
            withTempDir("learn-solutions") { home ->
                val projects = home.resolve("projects")
                Files.createDirectories(projects)
                val manager = ProjectManager.desktop(projects)
                try {
                    val learn = IdeServicesBackend(manager = manager).learn
                    val failures = ArrayList<String>()
                    var ran = 0
                    for (track in LearnContent.tracks) for (lesson in track.lessons) for (step in lesson.steps) {
                        // Hands-on CodeAssist steps are passed by doing things in the IDE; LearnIdeCheckTest covers them.
                        if (step !is LearnStepDef.Interactive || step.language.startsWith("ide-")) continue
                        ran++
                        val r = learn.check(lesson.id, step.id, step.solution.trimIndent())
                        if (!r.passed) failures += "${lesson.id}/${step.id}: ${r.message} ${r.diagnostics}\n${r.output}"
                        // Negative control for multi-case exercises: the starter must FAIL, which proves the
                        // checker really ran the program against each input rather than passing vacuously.
                        if (step.check.cases.isNotEmpty()) {
                            val starter = learn.check(lesson.id, step.id, step.starterCode.trimIndent())
                            if (starter.passed) failures += "${lesson.id}/${step.id}: the starter passes"
                            println("  ${lesson.id}: solution=${r.message.lineSequence().first()} | starter=${starter.message.lineSequence().first()}")
                        }
                    }
                    println("LearnSolutionsTest: $ran exercises checked, ${failures.size} failed")
                    assertTrue(failures.isEmpty(), failures.joinToString("\n\n"))
                } finally {
                    manager.dispose()
                }
            }
        }
    }
}
