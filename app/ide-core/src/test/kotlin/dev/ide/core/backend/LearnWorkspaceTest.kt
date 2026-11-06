package dev.ide.core.backend

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lessons in the full editor: the lesson's workspace becomes the active project, each step's file is put in
 * place for the editor, and Check runs in that same project.
 */
class LearnWorkspaceTest {

    @Test
    fun aLessonRunsInItsWorkspace() {
        runBlocking {
            withTempDir("learn-workspace") { home ->
                val projects = home.resolve("projects")
                Files.createDirectories(projects)
                val manager = ProjectManager.desktop(projects)
                try {
                    val backend = IdeServicesBackend(manager = manager)
                    val learn = backend.learn
                    val lesson = LearnContent.tracks.flatMap { it.lessons }.first { it.id == "kt-basics-capstone" }
                    val step = lesson.steps.filterIsInstance<LearnStepDef.Interactive>().single()

                    assertFalse(learn.openWorkspace("kt-basics-checkpoint"), "a lesson with no exercise stays in the player")
                    assertTrue(learn.openWorkspace(lesson.id))
                    val root = Paths.get(backend.project.rootPath).toAbsolutePath().normalize()
                    assertEquals(manager.learnWorkspaceDir("kotlin").toAbsolutePath().normalize(), root, "the workspace is the active project")
                    assertTrue(projects.toFile().listFiles().orEmpty().none { it.name == "kotlin" }, "kept out of the project list")

                    // The template's packaged sample is gone, so the lesson's Main is the only entry point.
                    val sources = Files.walk(root.resolve("app/src/main/kotlin")).use { s ->
                        s.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString() }.toList()
                    }
                    val path = assertNotNull(learn.prepareStep(lesson.id, step.id))
                    assertTrue(sources.none { it.endsWith(".kt") && !it.endsWith("Main.kt") }, "template sources cleared: $sources")
                    assertEquals(step.starterCode.trimIndent().trimEnd(), String(Files.readAllBytes(Paths.get(path))).trimEnd())

                    // Re-entering keeps the learner's work; Copy solution replaces it.
                    Files.write(Paths.get(path), "fun main() { /* mine */ }\n".toByteArray())
                    learn.prepareStep(lesson.id, step.id)
                    assertTrue("mine" in String(Files.readAllBytes(Paths.get(path))))
                    learn.prepareStep(lesson.id, step.id, step.solution.trimIndent())
                    assertTrue("passed++" in String(Files.readAllBytes(Paths.get(path))))

                    // Check runs in the workspace: the starter fails test 1, the solution passes all four.
                    val bad = learn.check(lesson.id, step.id, step.starterCode.trimIndent())
                    assertFalse(bad.passed, bad.message)
                    val good = learn.check(lesson.id, step.id, step.solution.trimIndent())
                    assertTrue(good.passed, "${good.message} ${good.diagnostics}\n${good.output}")

                    // Opening it again while it is already active does not swap the engine.
                    val epoch = backend.projects.projectEpoch.value
                    assertTrue(learn.openWorkspace(lesson.id))
                    assertEquals(epoch, backend.projects.projectEpoch.value)

                    // A hands-on CodeAssist step: seeds its files and opens one, before any run has happened (a
                    // null "current run" once made this throw and the editor opened nothing).
                    assertTrue(learn.openWorkspace("ca-ed-lightbulb"))
                    val tour = assertNotNull(learn.prepareStep("ca-ed-lightbulb", "ca-ed-lightbulb-i"), "tour step opens a file")
                    assertTrue(tour.endsWith("app/src/main/kotlin/Main.kt"), tour)
                    assertTrue("LocalDate.now()" in String(Files.readAllBytes(Paths.get(tour))))
                    val notYet = learn.check("ca-ed-lightbulb", "ca-ed-lightbulb-i", "")
                    assertFalse(notYet.passed, notYet.output)
                    Files.write(Paths.get(tour), ("import java.time.LocalDate\n\n" + String(Files.readAllBytes(Paths.get(tour)))).toByteArray())
                    val done = learn.check("ca-ed-lightbulb", "ca-ed-lightbulb-i", "")
                    assertTrue(done.passed, "${done.message}\n${done.output}\n${done.diagnostics}")
                    assertTrue(learn.openWorkspace(lesson.id))

                    // A step from another language's lesson has no file while this workspace is active.
                    val javaLesson = LearnContent.tracks.flatMap { it.lessons }.first { it.id == "java-basics-capstone" }
                    val javaStep = javaLesson.steps.filterIsInstance<LearnStepDef.Interactive>().single()
                    assertNull(learn.prepareStep(javaLesson.id, javaStep.id))
                } finally {
                    manager.dispose()
                }
            }
        }
    }
}
