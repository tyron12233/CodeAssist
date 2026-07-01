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
 * End-to-end of running an INSTANCE `main` — a plain `class Test { fun main() {} }` with no static entry point.
 * The JVM launcher can't run that (it requires a static `main`), but the run service detects it (index-backed,
 * no regex) and routes the desktop run through [dev.ide.build.engine.ReflectiveMainLauncher], which constructs
 * the class with its no-arg constructor and calls the instance method. Proves detection + the launcher path.
 */
class InstanceMainRunTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private fun createWorkspace(dir: Path) {
        val platform = PlatformCore()
        try {
            ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
            val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
            store.workspace.beginModification().apply { addProject("instmain", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            val mainSet = SourceSetTemplate(
                "main", DependencyScope.IMPLEMENTATION, mapOf("src/main/kotlin" to setOf(ContentRole.SOURCE)),
            )
            store.workspace.projects.single().beginModification().apply {
                addModule("app", javaLib).addSourceSet(mainSet)
                commit()
            }
            val f = dir.resolve("app/src/main/kotlin/com/example/App.kt")
            Files.createDirectories(f.parent)
            Files.writeString(
                f,
                """
                package com.example

                class App {
                    fun main() {
                        println("hello from instance main")
                    }
                }
                """.trimIndent(),
            )
            store.save()
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun runsAnInstanceMain() {
        val dir = Files.createTempDirectory("instance-main-run")
        createWorkspace(dir)
        IdeServices.open(dir).use { ide ->
            // Detection: a plain class with an instance `fun main` is offered as a run task.
            assertTrue(
                ide.build.runTasks().any { it.id == "run:app" },
                "an instance main() should be runnable: ${ide.build.runTasks().map { it.id }}",
            )

            ide.build.runTask("run:app")
            await(60_000) { ide.build.runConsole.value?.phase == RunPhase.Finished }

            val rc = ide.build.runConsole.value
            val text = rc?.transcript?.joinToString("") { it.text } ?: ""
            assertEquals(RunPhase.Finished, rc?.phase, "the program should finish; transcript:\n$text")
            assertEquals(0, rc?.exitCode, "the program should exit 0; transcript:\n$text")
            assertTrue("hello from instance main" in text, "the instance main() should have run:\n$text")
        }
        dir.toFile().deleteRecursively()
    }

    private fun await(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(40)
    }
}
