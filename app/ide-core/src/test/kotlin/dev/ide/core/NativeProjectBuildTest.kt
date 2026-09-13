package dev.ide.core

import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiLogLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Opt-in: assemble a native CodeAssist project from disk through the real desktop pipeline (resolve →
 * aapt2 → K2 + Compose → D8 → signed APK), the same engine the IDE runs on a device. For proving a Projects
 * Store submission before it is uploaded; [JetSnackBuildTest] is the Gradle-import counterpart.
 *
 *   RUN_NATIVE_BUILD=1 NATIVE_PROJECT_SRC=/abs/path/to/project ANDROID_HOME=... \
 *     ./gradlew :ide-core:test --tests '*NativeProjectBuildTest'
 *
 * Builds IN PLACE (the resolved-dependency cache and `build/` land under the project, as on a device), so
 * a second run is incremental. Set `NATIVE_BUILD_REPORT` to a file path to get the full build log there.
 */
class NativeProjectBuildTest {

    @Test
    fun assemblesDebugApk() {
        assumeTrue(System.getenv("RUN_NATIVE_BUILD") == "1", "opt-in: set RUN_NATIVE_BUILD=1")
        val src = System.getenv("NATIVE_PROJECT_SRC")?.let { Path.of(it) }
        assumeTrue(src != null && Files.isDirectory(src), "set NATIVE_PROJECT_SRC to a native project directory")
        val report = StringBuilder()
        fun line(text: String) {
            println(text)
            report.appendLine(text)
        }

        try {
            IdeServices.open(src!!).use { ide ->
                runBlocking {
                    line("=== resolving dependencies… ===")
                    withTimeout(20 * 60_000L) { ide.dependencies.retryDependencyResolution() }
                    ide.modules().forEach { m ->
                        line("module ${m.name}: unresolved=${ide.dependencies.declaredUnresolved(m)}")
                    }

                    val backend = IdeServicesBackend(ide)
                    val tasks = backend.build.runTasks().map { it.id }
                    line("=== run tasks: $tasks ===")
                    val assemble = tasks.firstOrNull { it.startsWith("assemble:") && it.endsWith(":debug") }
                        ?: tasks.first { it.startsWith("assemble:") }
                    line("=== assembling: $assemble ===")
                    backend.build.runTask(assemble)

                    val terminal = withTimeout(30 * 60_000L) {
                        backend.build.buildState.first {
                            it.status == RunStatus.Succeeded || it.status == RunStatus.Failed
                        }
                    }
                    line("=== STATUS: ${terminal.status} ===")
                    line("=== BUILD LOG ===")
                    terminal.log.forEach { line(it.toString()) }
                    line("=== DIAGNOSTICS (${terminal.diagnostics.size}) ===")
                    terminal.diagnostics.take(120).forEach { line(it.toString()) }
                    val apks = Files.walk(src).use { s -> s.filter { it.toString().endsWith(".apk") }.toList() }
                    line("=== APKS: $apks ===")

                    val errors = terminal.log.filter { it.level == UiLogLevel.Error }
                    assertEquals(
                        RunStatus.Succeeded, terminal.status,
                        "assemble should succeed; errors:\n${errors.joinToString("\n") { it.message }}",
                    )
                }
            }
        } finally {
            System.getenv("NATIVE_BUILD_REPORT")?.let { Path.of(it).writeText(report.toString()) }
        }
    }
}
