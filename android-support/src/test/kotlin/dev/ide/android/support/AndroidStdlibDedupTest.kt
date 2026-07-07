package dev.ide.android.support

import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the D8 "Duplicate class 'kotlin.collections.ArraysUtilJVM'" build failure: the IDE injects
 * its bundled `kotlin-stdlib-<v>.jar` (a non-Maven `.platform/…` path) into every Kotlin module, which
 * collides with any `kotlin-stdlib` the project resolves through Maven (directly or transitively) — two copies
 * of the same classes on the dex input. `resolveVersionConflicts` (applied by `module.classpath()`) can't
 * collapse them because the bundled jar carries no Maven coordinate, so both used to reach `dexJars`.
 * `AndroidLibraries.resolve` now runs the dex/compile sets through `MavenClasspath.dedupeForAndroidDex`, which
 * keys off the file name too — so exactly one `kotlin-stdlib` survives (the newest), and D8 sees no duplicate.
 */
class AndroidStdlibDedupTest {

    @Test
    fun collapsesBundledAndMavenKotlinStdlib() {
        val dir = Files.createTempDirectory("stdlib-dedup")
        val platform = PlatformCore()
        try {
            // The bundled stdlib: a non-Maven path, so it has no coordinate the graph-level dedup can read.
            val bundled = touchJar(dir.resolve(".platform/kotlin-stdlib-2.4.0.jar"))
            // A Maven-resolved stdlib at an older version, in repository layout.
            val maven = touchJar(dir.resolve("caches/org/jetbrains/kotlin/kotlin-stdlib/2.2.0/kotlin-stdlib-2.2.0.jar"))
            // An unrelated library that must pass through untouched.
            val other = touchJar(dir.resolve("caches/androidx/core/core/1.13.0/core-1.13.0.jar"))

            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }

            store.workspace.libraryTable.create("kotlin-stdlib").apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(bundled)); commit() }
            store.workspace.libraryTable.create("kotlin-stdlib-maven").apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(maven)); commit() }
            store.workspace.libraryTable.create("androidx-core").apply { kind = LibraryKind.JAR; addClassesRoot(store.vfs.fileFor(other)); commit() }

            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("kotlin-stdlib"), DependencyScope.IMPLEMENTATION))
                    addDependency(LibraryDependency(LibraryRef("kotlin-stdlib-maven"), DependencyScope.IMPLEMENTATION))
                    addDependency(LibraryDependency(LibraryRef("androidx-core"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }

            val libs = AndroidLibraries.resolve(store.workspace.projects.single().modules.single(), dir.resolve("exploded"))

            val stdlibs = libs.dexJars.filter { it.fileName.toString().startsWith("kotlin-stdlib") }
            assertEquals(1, stdlibs.size, "exactly one kotlin-stdlib should survive the dex input: ${libs.dexJars}")
            assertEquals("kotlin-stdlib-2.4.0.jar", stdlibs.single().fileName.toString(), "the newest (bundled) stdlib should win")
            assertTrue(libs.dexJars.any { it == other }, "the unrelated library must pass through: ${libs.dexJars}")
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun touchJar(p: Path): Path { Files.createDirectories(p.parent); Files.write(p, ByteArray(0)); return p }
}
