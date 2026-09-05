package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleTypeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DependencyScope.NATIVES] and the off-classpath phase it needs.
 *
 * A scope that lands on none of the three standard classpaths used to be unreachable: [dev.ide.model.Module.classpath]
 * matched it against no phase and fell through to the compile test, so asking for it always came back empty.
 * Such a scope is now its own phase, which is what lets the Android build find the prebuilt native libraries
 * a `natives` declaration resolved without ever putting them in front of a compiler.
 */
class NativesScopeTest {

    private fun build(store: ProjectModelStore, platform: dev.ide.platform.impl.PlatformCore) {
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        for (name in listOf("code", "natives")) {
            store.workspace.libraryTable.create(name).apply {
                kind = LibraryKind.JAR
                addClassesRoot(store.vfs.fileFor(store.rootPath.resolve("libs/$name.jar")))
                commit()
            }
        }
        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("engine", javaLib).apply {
                addDependency(LibraryDependency(LibraryRef("code"), DependencyScope.IMPLEMENTATION))
                addDependency(LibraryDependency(LibraryRef("natives"), DependencyScope.NATIVES))
            }
            addModule("app", javaLib).apply {
                addDependency(ModuleDependency(ModuleId("engine"), DependencyScope.IMPLEMENTATION, exported = true))
            }
            commit()
        }
    }

    private fun ProjectModelStore.module(name: String) =
        workspace.projects.single().modules.first { it.name == name }

    @Test
    fun nativesAreOnNoStandardClasspath() = withWorkspace { platform, store ->
        build(store, platform)
        val engine = store.module("engine")
        for (scope in listOf(
            DependencyScope.API,
            DependencyScope.IMPLEMENTATION,
            DependencyScope.COMPILE_ONLY,
            DependencyScope.RUNTIME_ONLY,
            DependencyScope.TEST_IMPLEMENTATION,
        )) {
            val paths = engine.classpath(scope).entries.map { it.root.path }
            assertTrue(
                paths.none { it.endsWith("natives.jar") },
                "a natives artifact holds prebuilt `.so` files and no classes, so it must stay off the " +
                    "$scope classpath; got $paths",
            )
        }
    }

    @Test
    fun requestingTheNativesPhaseReturnsExactlyWhatWasDeclaredInIt() = withWorkspace { platform, store ->
        build(store, platform)
        val paths = store.module("engine").classpath(DependencyScope.NATIVES).entries.map { it.root.path }
        assertEquals(1, paths.size, "only the natives declaration belongs to this phase; got $paths")
        assertTrue(paths.single().endsWith("natives.jar"), paths.single())
    }

    @Test
    fun aDependencyModulesNativesReachTheAppThatPackagesThem() = withWorkspace { platform, store ->
        build(store, platform)
        // The app packages the APK, so a library module's native libraries have to travel to it, the same
        // way that module's own `jniLibs` do.
        val paths = store.module("app").classpath(DependencyScope.NATIVES).entries.map { it.root.path }
        assertTrue(paths.any { it.endsWith("natives.jar") }, "the engine's natives must propagate; got $paths")
        assertTrue(paths.none { it.endsWith("code.jar") }, "only the natives phase propagates here; got $paths")
    }

    @Test
    fun theNativesScopeIsResolvableByItsPersistedNameAndConfigurationId() {
        // `module.toml` stores the name and groups a `[dependencies]` table under the id, so a project using
        // the scope has to reload with the real one rather than a permissively re-derived stand-in.
        assertEquals(DependencyScope.NATIVES, DependencyScope.valueOf("NATIVES"))
        assertEquals(DependencyScope.NATIVES, DependencyScope.byId("natives"))
        assertTrue(DependencyScope.NATIVES.offClasspath)
        for (scope in DependencyScope.entries - DependencyScope.NATIVES) {
            assertTrue(!scope.offClasspath, "$scope is a standard classpath phase")
        }
    }

    @Test
    fun aCoordinateRoundTripsThroughItsClassifier() {
        val coord = Coordinate("com.badlogicgames.gdx", "gdx-platform", "1.14.2", "natives-arm64-v8a")
        assertEquals("com.badlogicgames.gdx:gdx-platform:1.14.2:natives-arm64-v8a", coord.toString())
        assertEquals(coord, Coordinate.parseOrNull(coord.toString()))
        // Two classifiers of one module are different artifacts, not two versions of one.
        assertTrue(coord != coord.copy(classifier = "natives-armeabi-v7a"))
        // The three-part form still reads and prints exactly as before.
        val plain = Coordinate("g", "a", "1.0")
        assertEquals("g:a:1.0", plain.toString())
        assertEquals(plain, Coordinate.parseOrNull("g:a:1.0"))
        assertEquals(Coordinate("g", "a", ""), Coordinate.parseOrNull("g:a"))
        assertEquals(null, Coordinate.parseOrNull("nope"))
        assertEquals(null, Coordinate.parseOrNull("a:b:c:d:e"))
    }
}
