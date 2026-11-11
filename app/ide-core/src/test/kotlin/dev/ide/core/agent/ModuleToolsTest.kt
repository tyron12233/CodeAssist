package dev.ide.core.agent

import dev.ide.agent.AgentTool
import dev.ide.agent.ToolArgs
import dev.ide.core.IdeServices
import dev.ide.model.ModuleDependency
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent's module tools over the pure-Java demo (core <- util <- app): creating, linking and removing a
 * module, and reading a `module.toml` that was written or edited as a plain file into the OPEN project.
 */
class ModuleToolsTest {

    @Test
    fun `create_module registers a usable module, and the others can depend on it`(): Unit = withTempDir("agent-mod-create") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            var changes = 0
            val tools = ModuleTools.tools({ ide }) { changes++ }

            val types = tools.run("list_module_types")
            assertTrue("java-lib: " in types.content && "language_level: one of" in types.content, types.content)

            val created = tools.run("create_module", "name" to "feature", "type" to "java-lib", "parent_dir" to "features")
            assertFalse(created.isError, created.content)
            assertTrue(ide.modules().any { it.name == "feature" }, "the model has the module")
            assertTrue(Files.isRegularFile(dir.resolve("features/feature/module.toml")), "its manifest is on disk")
            assertTrue(Files.isDirectory(dir.resolve("features/feature/src/main/java")), "and so are its sources")
            assertEquals(1, changes, "the UI is told")

            val linked = tools.run("add_module_dependency", "module" to "app", "depends_on" to "feature")
            assertFalse(linked.isError, linked.content)
            val feature = ide.modules().first { it.name == "feature" }
            assertTrue(ide.modules().first { it.name == "app" }.dependencies.any { it is ModuleDependency && it.target == feature.id })
            assertTrue(tools.run("add_module_dependency", "module" to "feature", "depends_on" to "app").isError, "a cycle is refused")

            assertTrue(tools.run("create_module", "name" to "feature", "type" to "java-lib").isError, "a duplicate is refused")
            assertTrue(tools.run("create_module", "name" to "x", "type" to "no-such-type").isError, "an unknown type is refused")

            val removed = tools.run("remove_module", "name" to "feature")
            assertFalse(removed.isError, removed.content)
            assertFalse(ide.modules().any { it.name == "feature" })
            assertTrue(Files.isRegularFile(dir.resolve("features/feature/module.toml")), "its files stay")
            assertTrue(tools.run("remove_module", "name" to "feature").isError)
        }
        dir.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun `reload_project adopts a hand-written module and re-reads an edited manifest`(): Unit = withTempDir("agent-mod-reload") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val tools = ModuleTools.tools({ ide }) { }
            val nothing = tools.run("reload_project")
            assertFalse(nothing.isError, nothing.content)
            assertTrue("Nothing changed" in nothing.content, nothing.content)

            // What the agent used to do: copy a manifest into a new directory as a plain file.
            val manifest = Files.readString(dir.resolve("core/module.toml"))
            Files.createDirectories(dir.resolve("libs/extra/src/main/java"))
            Files.writeString(dir.resolve("libs/extra/module.toml"), manifest.replace("name = \"core\"", "name = \"extra\""))
            assertFalse(ide.modules().any { it.name == "extra" }, "the open project has not read it")

            val reloaded = tools.run("reload_project")
            assertFalse(reloaded.isError, reloaded.content)
            assertTrue("Added: extra" in reloaded.content, reloaded.content)
            val extra = ide.modules().first { it.name == "extra" }
            assertEquals(dir.resolve("libs/extra"), ide.moduleRoot(extra))
            assertTrue(ide.sourceRoots(extra).any { it == dir.resolve("libs/extra/src/main/java") }, "its sources are roots")
            assertTrue("extra" in Files.readString(dir.resolve(".platform/workspace.json")), "and it is saved as a module")

            // An edit made to a manifest as a file is read too.
            val before = ide.modules().first { it.name == "extra" }.languageLevel.name
            val other = if (before == "JAVA_17") "JAVA_11" else "JAVA_17"
            val path = dir.resolve("libs/extra/module.toml")
            Files.writeString(path, Files.readString(path).replace("languageLevel = \"$before\"", "languageLevel = \"$other\""))
            val edited = tools.run("reload_project")
            assertTrue("Re-read: extra" in edited.content, edited.content)
            assertEquals(other, ide.modules().first { it.name == "extra" }.languageLevel.name)

            // A manifest that does not parse leaves the module as it was and says why.
            Files.writeString(path, "[module\ntype = ")
            val broken = tools.run("reload_project")
            assertTrue(broken.isError && "extra" in broken.content, broken.content)
            assertTrue(ide.modules().any { it.name == "extra" }, "the module is kept")

            // A module whose directory is gone leaves the project.
            dir.resolve("libs").toFile().deleteRecursively()
            val gone = tools.run("reload_project")
            assertTrue("Removed (directory gone): extra" in gone.content, gone.content)
            assertFalse(ide.modules().any { it.name == "extra" })
            assertEquals(setOf("core", "util", "app"), ide.modules().map { it.name }.toSet())
        }
        dir.toFile().deleteRecursively()
        Unit
    }

    private fun List<AgentTool>.run(name: String, vararg pairs: Pair<String, String>) =
        runBlocking { first { it.spec.name == name }.execute(args(*pairs)) }

    private fun args(vararg pairs: Pair<String, String>) = object : ToolArgs {
        val map = pairs.toMap()
        override fun string(key: String) = map.getValue(key)
        override fun optString(key: String) = map[key]
        override fun int(key: String) = error("unused")
        override fun optInt(key: String): Int? = null
        override fun boolean(key: String) = error("unused")
        override fun optBoolean(key: String): Boolean? = null
        override fun stringList(key: String): List<String> = emptyList()
        override fun raw() = ""
    }
}
