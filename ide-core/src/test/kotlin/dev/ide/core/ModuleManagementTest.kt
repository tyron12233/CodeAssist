package dev.ide.core

import dev.ide.model.ModuleDependency
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises module management surfaced to the Modules screen: enumerating creatable module types, creating
 * and removing a module, module-on-module dependencies (incl. cycle prevention), and custom repositories.
 * Runs over the pure-Java demo (core ← util ← app), so no Android SDK is needed.
 */
class ModuleManagementTest {

    @Test
    fun availableTypesIncludeJavaAndAndroid() {
        val dir = Files.createTempDirectory("ide-modtypes")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val ids = ide.availableModuleTypes().map { it.id }
            assertTrue("java-lib" in ids, "java-lib is creatable")
            assertTrue("android-app" in ids && "android-lib" in ids, "android types are creatable")
            val javaLib = ide.availableModuleTypes().first { it.id == "java-lib" }
            assertTrue(javaLib.languageLevels.isNotEmpty() && javaLib.defaultFacets.isEmpty(), "java-lib has levels and no facets")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun createModuleAddsItWithSourceDirs() {
        val dir = Files.createTempDirectory("ide-createmod")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val r = ide.createModule("newlib", "java-lib", "JAVA_17", emptyMap())
            assertTrue(r.success, r.message)
            assertTrue(ide.modules().any { it.name == "newlib" }, "module is in the model")
            assertTrue(ide.configurableModules().any { it.name == "newlib" }, "module is in the settings list")
            val mod = ide.modules().first { it.name == "newlib" }
            val srcDir = mod.sourceSets.flatMap { it.contentRoots }.firstOrNull()
            assertNotNull(srcDir, "the default source set has a content root")
            assertTrue(Files.isDirectory(java.nio.file.Paths.get(srcDir.dir.path)), "its directory exists on disk")

            // duplicate + invalid names are rejected
            assertFalse(ide.createModule("newlib", "java-lib", null, emptyMap()).success, "duplicate rejected")
            assertFalse(ide.createModule("1bad name", "java-lib", null, emptyMap()).success, "invalid name rejected")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun moduleDependencyAddRemoveAndCycleGuard() {
        val dir = Files.createTempDirectory("ide-moddep")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            // app already depends on util (→ core). app may additionally depend on core, but not the reverse.
            val targets = ide.moduleDependencyTargets("app")
            assertTrue("core" in targets, "core is an eligible target for app")
            assertFalse("util" in targets, "util is already a dependency, so excluded")
            assertFalse("app" in targets, "a module is never its own target")

            // core → app would cycle (app transitively depends on core) and is blocked.
            assertFalse(ide.addModuleDependency("core", "app", "implementation").success, "cycle is rejected")

            // app → core is fine.
            assertTrue(ide.addModuleDependency("app", "core", "implementation").success)
            assertTrue(ide.modules().first { it.name == "app" }.dependencies
                .filterIsInstance<ModuleDependency>().any { it.target.value == "core" }, "the module dep was recorded")

            // removable via removeDependency (target name as the coordinate).
            assertTrue(ide.removeDependency("app", "core"))
            assertFalse(ide.modules().first { it.name == "app" }.dependencies
                .filterIsInstance<ModuleDependency>().any { it.target.value == "core" }, "the module dep was removed")

            // an `api` scope is exported; `implementation` is not (Gradle semantics, derived from scope).
            assertTrue(ide.addModuleDependency("app", "core", "api").success)
            val edge = ide.modules().first { it.name == "app" }.dependencies
                .filterIsInstance<ModuleDependency>().first { it.target.value == "core" }
            assertTrue(edge.exported, "an api module dependency is exported")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun removeModuleDropsDependersEdges() {
        val dir = Files.createTempDirectory("ide-rmmod")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            // util depends on core; removing core must also drop util's edge onto it.
            assertTrue(ide.removeModule("core"))
            assertFalse(ide.modules().any { it.name == "core" }, "core is gone from the model")
            assertFalse(ide.modules().first { it.name == "util" }.dependencies
                .filterIsInstance<ModuleDependency>().any { it.target.value == "core" }, "util's edge onto core was dropped")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun customRepositoriesPersistAndDedup() {
        val dir = Files.createTempDirectory("ide-repos")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val builtins = ide.repositories()
            assertTrue(builtins.all { it.builtin } && builtins.size >= 2, "the built-in repos are present and locked")

            assertTrue(ide.addRepository("Internal", "https://repo.example.com/maven"))
            assertTrue(ide.repositories().any { !it.builtin && it.url == "https://repo.example.com/maven" })

            assertFalse(ide.addRepository("dup", "https://repo.example.com/maven/"), "a trailing-slash duplicate is rejected")
            assertFalse(ide.addRepository("bad", "ftp://nope"), "a non-http URL is rejected")
            assertFalse(ide.removeRepository(builtins.first().url), "a built-in repo can't be removed")

            assertTrue(ide.removeRepository("https://repo.example.com/maven"))
            assertTrue(ide.repositories().none { it.url == "https://repo.example.com/maven" })
        }
        dir.toFile().deleteRecursively()
    }
}
