package dev.ide.core

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.ui.backend.RunPhase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end of the Kotlin console run + interactive stdin through the real [IdeServices] (the path the UI
 * drives): a `java-lib` module with a top-level `fun main()` that reads a line and echoes it. Proves
 *  - [IdeServices.runTasks] offers a `run` task for a Kotlin module (the top-level `fun main()` → `MainKt`
 *    facade is detected — this is the gap this feature closed), and
 *  - the program builds, runs (real `java` fork), and reads the stdin we feed via [IdeServices.sendRunInput],
 *    with the output + echoed input landing in [IdeServices.runConsole].
 */
class KotlinRunTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    /** Build a one-module Kotlin console workspace on disk and persist it, so [IdeServices.open] can reload it. */
    private fun createKotlinWorkspace(dir: Path) {
        val platform = PlatformCore()
        try {
            ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
            val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
            store.workspace.beginModification().apply { addProject("ktrun", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            val mainSet = SourceSetTemplate(
                "main", DependencyScope.IMPLEMENTATION, mapOf("src/main/kotlin" to setOf(ContentRole.SOURCE)),
            )
            store.workspace.projects.single().beginModification().apply {
                addModule("app", javaLib).addSourceSet(mainSet)
                commit()
            }
            val f = dir.resolve("app/src/main/kotlin/com/example/Main.kt")
            Files.createDirectories(f.parent)
            Files.writeString(
                f,
                """
                package com.example

                fun main() {
                    print("Enter name: ")
                    val line = readLine()
                    println("Hello, ${'$'}line!")
                }
                """.trimIndent(),
            )
            store.save()
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun runsAKotlinConsoleAppAndFeedsStdin() {
        val dir = Files.createTempDirectory("kotlin-run")
        createKotlinWorkspace(dir)
        IdeServices.open(dir).use { ide ->
            // Detection: a top-level Kotlin `fun main()` must surface a `run` task (the feature's core fix).
            assertTrue(
                ide.build.runTasks().any { it.id == "run:app" },
                "a Kotlin main() should be runnable: ${ide.build.runTasks().map { it.id }}",
            )

            ide.build.runTask("run:app")

            // Wait until the program is executing (build finished, stdin accepted) or it already finished.
            await(20_000) { ide.build.runConsole.value?.phase == RunPhase.Running || ide.build.runConsole.value?.phase == RunPhase.Finished }
            val started = ide.build.runConsole.value
            assertTrue(started != null && started.phase != RunPhase.Building, "the program never started; transcript=${started?.transcript}")

            ide.build.sendRunInput("World")

            await(20_000) { ide.build.runConsole.value?.phase == RunPhase.Finished }
            val rc = ide.build.runConsole.value!!
            val text = rc.transcript.joinToString("") { it.text }
            assertEquals(0, rc.exitCode, "the program should exit 0; transcript:\n$text")
            assertTrue("Enter name:" in text, "the prompt (no trailing newline) should appear:\n$text")
            assertTrue("Hello, World!" in text, "the program should echo the fed stdin:\n$text")
        }
        dir.toFile().deleteRecursively()
    }

    private fun await(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(40)
    }
}
