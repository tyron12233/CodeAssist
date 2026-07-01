package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies that an `implementation` dependency of a dependency is absent from the depender's compile
 * classpath, while an `api` dependency is present. Also covers the runtime-closure and content-hash
 * properties of [dev.ide.model.ClasspathSnapshot].
 */
class ClasspathAssemblyTest {

    /** consumer → lib;  lib --api--> apiLib,  lib --implementation--> implLib. */
    private fun build(store: ProjectModelStore, platform: dev.ide.platform.impl.PlatformCore) {
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.libraryTable.create("apiLib").apply {
            kind = LibraryKind.JAR
            addClassesRoot(store.vfs.fileFor(store.rootPath.resolve("libs/apiLib.jar")))
            commit()
        }
        store.workspace.libraryTable.create("implLib").apply {
            kind = LibraryKind.JAR
            addClassesRoot(store.vfs.fileFor(store.rootPath.resolve("libs/implLib.jar")))
            commit()
        }
        store.workspace.beginModification().apply {
            addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("lib", javaLib).apply {
                addDependency(LibraryDependency(LibraryRef("apiLib"), DependencyScope.API, exported = true))
                addDependency(LibraryDependency(LibraryRef("implLib"), DependencyScope.IMPLEMENTATION, exported = false))
            }
            addModule("consumer", javaLib).apply {
                addDependency(ModuleDependency(ModuleId("lib"), DependencyScope.API, exported = true))
            }
            commit()
        }
    }

    private fun ProjectModelStore.consumer() = workspace.projects.single().modules.first { it.name == "consumer" }

    @Test
    fun apiDependencyOfADependencyIsOnCompileClasspathButImplementationIsNot() = withWorkspace { platform, store ->
        build(store, platform)
        val compile = store.consumer().classpath(DependencyScope.IMPLEMENTATION).entries
        val paths = compile.map { it.root.path }

        assertTrue(paths.any { it.contains("apiLib.jar") }, "api dep of dependency must be present: $paths")
        assertTrue(paths.none { it.contains("implLib.jar") }, "implementation dep of dependency must be absent: $paths")
        assertTrue(compile.any { it.kind == ClasspathEntryKind.MODULE_OUTPUT }, "dependency module output must be present")
    }

    @Test
    fun runtimeClasspathIncludesTheFullClosure() = withWorkspace { platform, store ->
        build(store, platform)
        val runtime = store.consumer().classpath(DependencyScope.RUNTIME_ONLY).entries.map { it.root.path }
        assertTrue(runtime.any { it.contains("apiLib.jar") })
        assertTrue(runtime.any { it.contains("implLib.jar") }, "runtime closure must include implementation deps: $runtime")
    }

    @Test
    fun crossModuleVersionConflictKeepsOnlyTheNewestLibraryVersion() = withWorkspace { platform, store ->
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        // Two libraries' closures each carry a different version of the SAME artifact at its Maven-layout path
        // (`…/<name>/<version>/<name>-<version>.jar`) — exactly how independent resolution stores them. The
        // conflicting jar is a *transitive* member named under a different primary, so only the path identifies it.
        val cache = "caches/resolved-deps/androidx/activity/activity"
        store.workspace.libraryTable.create("com.example:old-feature:1.0").apply {
            kind = LibraryKind.JAR
            addClassesRoot(store.vfs.fileFor(store.rootPath.resolve("$cache/1.7.0/activity-1.7.0.jar")))
            commit()
        }
        store.workspace.libraryTable.create("com.example:new-feature:1.0").apply {
            kind = LibraryKind.JAR
            addClassesRoot(store.vfs.fileFor(store.rootPath.resolve("$cache/1.8.0/activity-1.8.0.jar")))
            commit()
        }
        store.workspace.beginModification().apply { addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("old", javaLib).apply {
                addDependency(LibraryDependency(LibraryRef("com.example:old-feature:1.0"), DependencyScope.API, exported = true))
            }
            addModule("new", javaLib).apply {
                addDependency(LibraryDependency(LibraryRef("com.example:new-feature:1.0"), DependencyScope.API, exported = true))
            }
            addModule("consumer", javaLib).apply {
                addDependency(ModuleDependency(ModuleId("old"), DependencyScope.API, exported = true))
                addDependency(ModuleDependency(ModuleId("new"), DependencyScope.API, exported = true))
            }
            commit()
        }
        val consumer = store.workspace.projects.single().modules.first { it.name == "consumer" }
        val runtime = consumer.classpath(DependencyScope.RUNTIME_ONLY).entries.map { it.root.path }
        assertTrue(runtime.any { it.contains("activity/1.8.0/activity-1.8.0.jar") }, "newest version must win: $runtime")
        assertTrue(runtime.none { it.contains("activity/1.7.0/activity-1.7.0.jar") }, "superseded version must be dropped: $runtime")
    }

    @Test
    fun variantFilterSelectsConfigQualifiedDirectDependencies() = withWorkspace { platform, store ->
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        for (n in listOf("sharedLib", "debugLib", "releaseLib")) {
            store.workspace.libraryTable.create(n).apply {
                kind = LibraryKind.JAR
                addClassesRoot(store.vfs.fileFor(store.rootPath.resolve("libs/$n.jar")))
                commit()
            }
        }
        store.workspace.beginModification().apply { addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("app", javaLib).apply {
                addDependency(LibraryDependency(LibraryRef("sharedLib"), DependencyScope.IMPLEMENTATION))
                addDependency(LibraryDependency(LibraryRef("debugLib"), DependencyScope.IMPLEMENTATION, variant = "debug"))
                addDependency(LibraryDependency(LibraryRef("releaseLib"), DependencyScope.IMPLEMENTATION, variant = "release"))
            }
            commit()
        }
        val app = store.workspace.projects.single().modules.first { it.name == "app" }

        val debug = app.classpath(DependencyScope.IMPLEMENTATION, variant = setOf("main", "debug")).entries.map { it.root.path }
        assertTrue(debug.any { it.contains("sharedLib.jar") }, "a shared (unqualified) dep is in every variant: $debug")
        assertTrue(debug.any { it.contains("debugLib.jar") }, "a debug-qualified dep is in the debug variant: $debug")
        assertTrue(debug.none { it.contains("releaseLib.jar") }, "a release-qualified dep is absent from debug: $debug")

        val all = app.classpath(DependencyScope.IMPLEMENTATION, variant = null).entries.map { it.root.path }
        assertTrue(
            all.any { it.contains("debugLib.jar") } && all.any { it.contains("releaseLib.jar") },
            "a null variant filter includes every entry (back-compat): $all",
        )
    }

    @Test
    fun variantFilterAppliesToTheModuleDependencyClosure() = withWorkspace { platform, store ->
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.libraryTable.create("debugApiLib").apply {
            kind = LibraryKind.JAR
            addClassesRoot(store.vfs.fileFor(store.rootPath.resolve("libs/debugApiLib.jar")))
            commit()
        }
        store.workspace.beginModification().apply { addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("lib", javaLib).apply {
                addDependency(LibraryDependency(LibraryRef("debugApiLib"), DependencyScope.API, exported = true, variant = "debug"))
            }
            addModule("consumer", javaLib).apply {
                addDependency(ModuleDependency(ModuleId("lib"), DependencyScope.API, exported = true))
            }
            commit()
        }
        val consumer = store.workspace.projects.single().modules.first { it.name == "consumer" }
        val debug = consumer.classpath(DependencyScope.IMPLEMENTATION, variant = setOf("main", "debug")).entries.map { it.root.path }
        val release = consumer.classpath(DependencyScope.IMPLEMENTATION, variant = setOf("main", "release")).entries.map { it.root.path }
        assertTrue(debug.any { it.contains("debugApiLib.jar") }, "the lib's debug api dep flows into the consumer's debug classpath: $debug")
        assertTrue(release.none { it.contains("debugApiLib.jar") }, "the lib's debug api dep is absent from the consumer's release classpath: $release")
    }

    @Test
    fun fingerprintIsDeterministicAndDistinguishesClasspaths() = withWorkspace { platform, store ->
        build(store, platform)
        val consumer = store.consumer()
        assertEquals(
            consumer.classpath(DependencyScope.IMPLEMENTATION).fingerprint(),
            consumer.classpath(DependencyScope.IMPLEMENTATION).fingerprint(),
        )
        assertNotEquals(
            consumer.classpath(DependencyScope.IMPLEMENTATION).fingerprint(),
            consumer.classpath(DependencyScope.RUNTIME_ONLY).fingerprint(),
        )
    }
}
