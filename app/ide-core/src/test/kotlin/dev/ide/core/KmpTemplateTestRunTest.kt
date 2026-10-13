package dev.ide.core

import dev.ide.core.gradle.GradleKmp
import dev.ide.core.project.ProjectManager
import dev.ide.model.template.TemplateArgs
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.TestOutput
import dev.ide.ui.backend.UiTestStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The KMP library template's `commonTest/GreetingTest.kt` runs on the desktop: the template declares only
 * `kotlin-test`, the import adds the JUnit framework the Kotlin plugin would, and the `test:` task compiles
 * commonTest against the module and runs it. Needs its dependencies resolved, so it is skipped when they cannot
 * be (offline with a cold cache).
 */
@Timeout(600, unit = TimeUnit.SECONDS)
class KmpTemplateTestRunTest {

    @Test
    fun theKmpLibraryTemplatesCommonTestRuns() {
        withTempDir("kmp-template-tests") { tmp ->
            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                manager.create("kmp-library", mapOf(TemplateArgs.NAME to "Shared Lib", TemplateArgs.PACKAGE to "com.example.shared")).use { ide ->
                    runBlocking { withTimeout(300_000) { ide.dependencies.retryDependencyResolution() } }
                    val shared = ide.modules().single()
                    assumeTrue(ide.dependencies.declaredUnresolved(shared).isEmpty(), "dependencies unresolved: ${ide.dependencies.declaredUnresolved(shared)}")

                    assertTrue(ide.build.runTasks().any { it.id == "test:shared" }, "offered: ${ide.build.runTasks().map { it.id }}")
                    runAndExpectPass(ide, "desktop")

                    // The same module imported for its Android target: an android-lib, whose tests build against
                    // its variant's output and android.jar.
                    assertTrue(runBlocking { ide.switchKmpTarget(GradleKmp.Platform.ANDROID) }.ok)
                    assertEquals("android-lib", ide.modules().single().type.id)
                    runBlocking { withTimeout(300_000) { ide.dependencies.retryDependencyResolution() } }
                    assertTrue(ide.build.runTasks().any { it.id == "test:shared" }, "offered on Android: ${ide.build.runTasks().map { it.id }}")
                    runAndExpectPass(ide, "android")
                }
            } finally {
                manager.dispose()
            }
        }
    }

    private fun runAndExpectPass(ide: IdeServices, what: String) {
        val before = ide.build.runConsole.value?.id
        ide.build.runTask("test:shared")
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            val rc = ide.build.runConsole.value
            if (rc != null && rc.id != before && rc.phase == RunPhase.Finished) break
            Thread.sleep(50)
        }
        val rc = assertNotNull(ide.build.runConsole.value)
        val log = ide.build.buildState.value.log.joinToString("\n") { it.message }
        val raw = rc.transcript.joinToString("") { it.text }.replace(TestOutput.MARK, "<M>")
        val tests = assertNotNull(TestOutput.split(rc.transcript).tests, "$what: no results; log:\n$log")
        assertTrue(tests.cases.isNotEmpty() && tests.finished, "$what transcript:\n$raw\nlog:\n$log")
        for (case in tests.cases) {
            assertEquals("com.example.shared.GreetingTest", case.className)
            assertEquals(UiTestStatus.Passed, case.status, "$what ${case.name}: ${case.message}\n$raw")
        }
        assertEquals(0, rc.exitCode, "$what\n$log")
    }
}
