package dev.ide.core

import dev.ide.model.LanguageLevel
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiLogLevel
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test

/**
 * End-to-end: convert a real Gradle Compose sample (JetSnack) to a native CodeAssist project and assemble a
 * debug APK — the "compile it on device" path (desktop runs the same build engine). Opt-in and heavy (Maven
 * resolution of the whole Compose/AndroidX graph + a full aapt2/kotlinc+Compose/D8/sign build), so gated:
 *   RUN_JETSNACK_BUILD=1 JETSNACK_SRC=/abs/path/to/Jetsnack ANDROID_HOME=... \
 *     ./gradlew :ide-core:test --tests '*JetSnackBuildTest'
 */
class JetSnackBuildTest {

    @Test
    fun importsAndAssemblesDebugApk() {
        assumeTrue(System.getenv("RUN_JETSNACK_BUILD") == "1", "opt-in: set RUN_JETSNACK_BUILD=1")
        val src = System.getenv("JETSNACK_SRC")?.let { Path.of(it) }
        assumeTrue(src != null && Files.isDirectory(src), "set JETSNACK_SRC to a Jetsnack checkout")
        val sdk = IdeServices.defaultDesktopSdk()

        withTempDir("jetsnack-build") { tmp ->
            copyProject(src!!, tmp)
            check(IdeServices.importExternalProjectAt(tmp, sdk, LanguageLevel.JAVA_17)) { "import failed" }

            IdeServices.open(tmp).use { ide ->
                runBlocking {
                    println("=== resolving dependencies… ===")
                    withTimeout(20 * 60_000L) { ide.dependencies.retryDependencyResolution() }
                    ide.modules().forEach { m ->
                        println("module ${m.name}: unresolved=${ide.dependencies.declaredUnresolved(m)}")
                    }

                    val backend = IdeServicesBackend(ide)
                    val tasks = backend.build.runTasks().map { it.id }
                    println("=== run tasks: $tasks ===")
                    val assemble = tasks.firstOrNull { it.startsWith("assemble:") && it.endsWith(":debug") }
                        ?: tasks.first { it.startsWith("assemble:") }
                    println("=== assembling: $assemble ===")
                    backend.build.runTask(assemble)

                    val terminal = withTimeout(30 * 60_000L) {
                        backend.build.buildState.first {
                            it.status == RunStatus.Succeeded || it.status == RunStatus.Failed
                        }
                    }
                    println("=== STATUS: ${terminal.status} ===")
                    println("=== BUILD LOG ===")
                    terminal.log.forEach { println(it) }
                    println("=== DIAGNOSTICS (${terminal.diagnostics.size}) ===")
                    terminal.diagnostics.take(60).forEach { println(it) }

                    val errors = terminal.log.filter { it.level == UiLogLevel.Error }
                    assertEquals(
                        RunStatus.Succeeded, terminal.status,
                        "assemble should succeed; errors:\n${errors.joinToString("\n") { it.message }}",
                    )
                }
            }
        }
    }

    /** Copy a Gradle project, skipping Gradle's own output/metadata dirs. */
    private fun copyProject(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            for (p in stream) {
                val rel = src.relativize(p)
                if ((0 until rel.nameCount).any { rel.getName(it).name in SKIP }) continue
                val target = dst.resolve(rel.toString())
                if (Files.isDirectory(p)) Files.createDirectories(target)
                else { Files.createDirectories(target.parent); Files.copy(p, target) }
            }
        }
    }

    private companion object {
        val SKIP = setOf("build", ".gradle", ".git", ".idea", "buildscripts")
    }
}
