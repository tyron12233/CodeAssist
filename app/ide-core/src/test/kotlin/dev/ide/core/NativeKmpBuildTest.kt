package dev.ide.core

import dev.ide.core.project.ProjectManager
import dev.ide.model.template.TemplateArgs
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiLogLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A native Compose Multiplatform project (the template with Gradle off) built for real on both targets: the
 * desktop build first, then switched to Android natively and assembled as an APK. Opt-in and network-heavy
 * (it resolves Compose for both platforms): `RUN_EXT_BUILD=1 ./gradlew :ide-core:test --tests '*NativeKmpBuildTest'`.
 */
class NativeKmpBuildTest {

    @Test
    fun buildsBothTargetsOfANativeProject() {
        assumeTrue(System.getenv("RUN_EXT_BUILD") == "1", "opt-in: set RUN_EXT_BUILD=1")
        withTempDir("native-kmp-build") { tmp ->
            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                manager.create(
                    "kmp-compose-app",
                    mapOf(TemplateArgs.NAME to "Native App", TemplateArgs.PACKAGE to "com.example.nativeapp", "gradle" to "false"),
                ).use { ide ->
                    val backend = IdeServicesBackend(ide)
                    fun build(prefix: String) = runBlocking {
                        withTimeout(20 * 60_000L) { ide.dependencies.retryDependencyResolution() }
                        val tasks = backend.build.runTasks().map { it.id }
                        val task = tasks.first { it.startsWith(prefix) }
                        println("=== $task (of $tasks) ===")
                        backend.build.runTask(task)
                        val terminal = withTimeout(30 * 60_000L) {
                            backend.build.buildState.first { it.status == RunStatus.Succeeded || it.status == RunStatus.Failed }
                        }
                        val errors = terminal.log.filter { it.level == UiLogLevel.Error }.joinToString("\n") { it.message }
                        assertEquals(RunStatus.Succeeded, terminal.status, "$task failed:\n$errors")
                    }

                    build("build:composeApp")
                    val switched = runBlocking { backend.projects.setKmpTarget("android") }
                    assertTrue(switched.ok, switched.message)
                    build("assemble:composeApp:debug")
                    val apk = Files.walk(ide.workspaceRoot.resolve("composeApp/build")).use { s ->
                        s.filter { it.toString().endsWith("-debug.apk") }.findFirst().orElse(null)
                    }
                    assertTrue(apk != null, "a debug APK was assembled")
                }
            } finally {
                manager.dispose()
            }
        }
    }
}
