package dev.ide.core

import dev.ide.core.gradle.GradleKmp
import dev.ide.model.LanguageLevel
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiLogLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Imports any Gradle checkout the way a user's "Open project" does, resolves it, and runs one of its tasks
 * on CodeAssist's own build engine: the loop for trying projects from the wild (the KMP wizard's output,
 * JetBrains' samples) and fixing what breaks. Opt-in and network-heavy:
 *
 *   RUN_EXT_BUILD=1 EXT_SRC=/abs/checkout [EXT_KMP_TARGET=android|jvm] [EXT_TASK=assemble:] \
 *     ./gradlew :ide-core:test --tests '*ExternalProjectBuildTest'
 *
 * `EXT_KMP_TARGET` picks the target a Multiplatform module is imported for (the phone imports Android, the
 * desktop the JVM). `EXT_TASK` is a substring of the run-task id to run; by default a debug `assemble`.
 * `EXT_SUBDIR` builds one project inside a repository of several.
 */
class ExternalProjectBuildTest {

    @Test
    fun importsResolvesAndBuilds() {
        assumeTrue(System.getenv("RUN_EXT_BUILD") == "1", "opt-in: set RUN_EXT_BUILD=1")
        val src = System.getenv("EXT_SRC")?.let { Path.of(it) }
            ?.let { s -> System.getenv("EXT_SUBDIR")?.let { s.resolve(it) } ?: s }
        assumeTrue(src != null && Files.isDirectory(src), "set EXT_SRC to a Gradle checkout")
        val sdk = IdeServices.defaultDesktopSdk()

        // EXT_WORK keeps the imported copy for inspection after the run (it is emptied first).
        val keep = System.getenv("EXT_WORK")?.let { Path.of(it) }
        if (keep != null) {
            if (Files.exists(keep)) Files.walk(keep).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            Files.createDirectories(keep)
            run(src!!, keep, sdk)
        } else withTempDir("ext-build") { tmp -> run(src!!, tmp, sdk) }
    }

    private fun run(src: Path, tmp: Path, sdk: dev.ide.model.impl.SdkData) {
        run {
            copyProject(src, tmp)
            System.getenv("EXT_KMP_TARGET")?.let {
                GradleKmp.setPreference(tmp, GradleKmp.Platform.valueOf(it.uppercase()))
            }
            check(IdeServices.importExternalProjectAt(tmp, sdk, LanguageLevel.JAVA_17)) { "import failed" }
            tmp.resolve(".platform/imported-from-gradle").takeIf { Files.isRegularFile(it) }?.let {
                println("=== IMPORT NOTES ===\n${it.readText()}")
            }

            IdeServices.open(tmp).use { ide ->
                runBlocking {
                    withTimeout(20 * 60_000L) { ide.dependencies.retryDependencyResolution() }
                    ide.modules().forEach { m ->
                        println("module ${m.name}: unresolved=${ide.dependencies.declaredUnresolved(m)}")
                    }
                    Files.walk(tmp).use { s ->
                        s.filter { it.name == "module.toml" }.forEach { println("--- $it\n${it.readText()}") }
                    }

                    val backend = IdeServicesBackend(ide)
                    val tasks = backend.build.runTasks().map { it.id }
                    println("=== run tasks: $tasks ===")
                    val wanted = System.getenv("EXT_TASK")
                    val task = when {
                        wanted != null -> tasks.first { wanted in it }
                        else -> tasks.firstOrNull { it.startsWith("assemble:") && it.endsWith(":debug") }
                            ?: tasks.first { it.startsWith("assemble:") || it.startsWith("build:") }
                    }
                    println("=== running: $task ===")
                    backend.build.runTask(task)

                    val terminal = withTimeout(30 * 60_000L) {
                        backend.build.buildState.first {
                            it.status == RunStatus.Succeeded || it.status == RunStatus.Failed
                        }
                    }
                    println("=== STATUS: ${terminal.status} ===")
                    terminal.log.forEach { println(it) }
                    println("=== DIAGNOSTICS (${terminal.diagnostics.size}) ===")
                    terminal.diagnostics.take(80).forEach { println(it) }

                    val errors = terminal.log.filter { it.level == UiLogLevel.Error }
                    assertEquals(
                        RunStatus.Succeeded, terminal.status,
                        "$task should succeed; errors:\n${errors.joinToString("\n") { it.message }}",
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
        val SKIP = setOf("build", ".gradle", ".git", ".idea", ".kotlin")
    }
}
