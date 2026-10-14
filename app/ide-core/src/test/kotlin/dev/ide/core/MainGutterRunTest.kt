package dev.ide.core

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.open
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.RunGutter
import dev.ide.ui.backend.RunPhase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's run marks beside each `main`: one per entry point in a console module's main sources, each
 * carrying the `run:<module>:<class>` task that launches THAT class, not the module's default main.
 */
class MainGutterRunTest {

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
            store.workspace.beginModification().apply { addProject("mains", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", javaLib).apply {
                    addSourceSet(SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/kotlin" to setOf(ContentRole.SOURCE))))
                    addSourceSet(SourceSetTemplate("test", DependencyScope.TEST_IMPLEMENTATION, mapOf("src/test/kotlin" to setOf(ContentRole.SOURCE))))
                }
                commit()
            }
            write(dir.resolve("app/src/main/kotlin/com/example/App.kt"), "package com.example\n\nfun main() {\n    println(\"from app\")\n}\n")
            write(
                dir.resolve("app/src/main/kotlin/com/example/Tool.kt"),
                "package com.example\n\nobject Tool {\n    @JvmStatic\n    fun main(args: Array<String>) {\n        println(\"from tool\")\n    }\n}\n",
            )
            write(dir.resolve("app/src/test/kotlin/com/example/Scratch.kt"), "package com.example\n\nfun main() {}\n")
            store.save()
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun marksEachMainAndRunsTheOneTapped() {
        withTempDir("main-gutter") { dir ->
            createWorkspace(dir)
            IdeServices.open(dir).use { ide ->
                val tool = dir.resolve("app/src/main/kotlin/com/example/Tool.kt")
                val marks = runBlocking { ide.editorDecorations(tool, Files.readString(tool)) }.gutter
                    .filter { it.actionId?.startsWith(RunGutter.ACTION_PREFIX) == true }
                val mark = marks.single()
                assertTrue("fun main" in Files.readAllLines(tool)[mark.line], "the mark sits on main's line")
                val task = mark.actionId!!.removePrefix(RunGutter.ACTION_PREFIX)
                assertEquals("run:app:com.example.Tool", task)
                assertEquals("Run Tool", RunGutter.option(task).label)

                // A main in a test root is left to the test marks.
                val scratch = dir.resolve("app/src/test/kotlin/com/example/Scratch.kt")
                assertTrue(runBlocking { ide.editorDecorations(scratch, Files.readString(scratch)) }.gutter
                    .none { it.actionId?.startsWith(RunGutter.ACTION_PREFIX) == true })

                // The module's default would be AppKt; the tapped mark runs Tool.
                ide.build.runTask(task)
                await(120_000) { ide.build.runConsole.value?.phase == RunPhase.Finished }
                val rc = ide.build.runConsole.value
                val text = rc?.transcript?.joinToString("") { it.text } ?: ""
                assertEquals(0, rc?.exitCode, "the program should exit 0; transcript:\n$text")
                assertTrue("from tool" in text && "from app" !in text, "Tool's main should have run:\n$text")
            }
        }
    }

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun await(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(40)
    }
}
