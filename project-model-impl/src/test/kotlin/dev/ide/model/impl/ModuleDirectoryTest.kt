package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.DependencyScope
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.ModuleTypeRegistry
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.withTempDir
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A module knows where it lives.
 *
 * Consumers used to recover the module directory as `outputDir.parent.parent`, which worked only because the
 * built-in output convention happens to be two levels deep. That made the module's location a consequence of
 * a compiled-language build layout, so a module with a different output path (or none at all, once
 * [dev.ide.model.Module.outputDir] became optional) silently resolved to the wrong directory.
 */
class ModuleDirectoryTest {

    private fun open(dir: java.nio.file.Path, platform: PlatformCore): ProjectModelStore {
        platform.registerTestTypes()
        val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
        store.workspace.beginModification().apply {
            addProject("root", BuildSystemId.NATIVE, store.vfs.root()); commit()
        }
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.projects.single().beginModification().apply {
            addModule("app", javaLib).apply { dirRelPath = "apps/app" }
            addModule("core", javaLib)
            commit()
        }
        return store
    }

    @Test
    fun aModuleDirectoryIsItsOwn() = withTempDir("module-dir") { dir ->
        val platform = PlatformCore()
        try {
            val store = open(dir, platform)
            val modules = store.workspace.projects.single().modules

            val app = modules.single { it.name == "app" }
            assertEquals(
                dir.resolve("apps").resolve("app").toAbsolutePath().normalize(),
                Paths.get(app.dir.path).toAbsolutePath().normalize(),
                "a nested module's directory is the one it declared, not one derived from its name",
            )

            // `core` declared no dirRelPath, so it defaults to the module name.
            val core = modules.single { it.name == "core" }
            assertEquals(
                dir.resolve("core").toAbsolutePath().normalize(),
                Paths.get(core.dir.path).toAbsolutePath().normalize(),
            )
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun theOutputDirectoryLivesUnderItButIsNotHowItIsFound() = withTempDir("module-out") { dir ->
        val platform = PlatformCore()
        try {
            val app = open(dir, platform).workspace.projects.single().modules.single { it.name == "app" }

            val moduleDir = Paths.get(app.dir.path).toAbsolutePath().normalize()
            val out = Paths.get(assertNotNullOutput(app.outputDir?.path)).toAbsolutePath().normalize()
            assertTrue(out.startsWith(moduleDir), "$out is under $moduleDir")
            // The old derivation, stated so the coupling this removed stays visible: it holds only for the
            // built-in `build/classes` convention, which is exactly why it was the wrong thing to depend on.
            assertEquals(moduleDir, out.parent.parent)
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun aModuleWithNoDependenciesHasAnEmptyClasspath() = withTempDir("module-cp") { dir ->
        val platform = PlatformCore()
        try {
            val core = open(dir, platform).workspace.projects.single().modules.single { it.name == "core" }
            val cp = core.classpath(DependencyScope.IMPLEMENTATION)
            assertTrue(cp.entries.isEmpty())
            assertEquals(ClasspathSnapshot.EMPTY.fingerprint(), cp.fingerprint())
        } finally {
            platform.dispose()
        }
    }

    private fun assertNotNullOutput(path: String?): String =
        requireNotNull(path) { "a java-lib module compiles, so it declares an output directory" }
}
