package dev.ide.build.engine

import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.SourceSet
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/*
 * The paths and classpaths of a module's TEST compilation: the source sets `sourceRootDirs` leaves out (see
 * `producesOutput`), compiled into their own output against the main output plus the test-scoped classpath they
 * were written for. Kept apart from the main output in every respect (directories, incremental state), so a test
 * build never invalidates or pollutes the module's own classes.
 */

/** A test-only source set: on neither the compile nor the runtime classpath, but on the test one. An Android
 *  module's `androidTest` sets are instrumented tests, which run on a device under a test APK, not here. */
fun isTestSourceSet(sourceSet: SourceSet): Boolean =
    !sourceSet.scope.onCompile && !sourceSet.scope.onRuntime && sourceSet.scope.onTest &&
        !sourceSet.name.startsWith("androidTest")

/** An Android module's instrumented-test source set (`androidTest`, `androidTestDebug`, …): test-only like
 *  [isTestSourceSet], but built into a test APK that runs on a device rather than on the JVM. */
fun isAndroidTestSourceSet(sourceSet: SourceSet): Boolean =
    !sourceSet.scope.onCompile && !sourceSet.scope.onRuntime && sourceSet.scope.onTest &&
        sourceSet.name.startsWith("androidTest")

/** The SOURCE and GENERATED roots of [module]'s instrumented-test source sets that exist on disk. */
fun androidTestSourceRootDirs(module: Module): List<Path> = collapseNestedRoots(
    module.sourceSets.filter(::isAndroidTestSourceSet)
        .flatMap { it.contentRoots }
        .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
        .map { Paths.get(it.dir.path) },
).filter { Files.isDirectory(it) }

/** Whether [module] has any instrumented test source. */
fun hasAndroidTestSources(module: Module): Boolean =
    androidTestSourceRootDirs(module).any { root -> filesUnder(listOf(root), ".kt").isNotEmpty() || filesUnder(listOf(root), ".java").isNotEmpty() }

/** The `.java` / `.kt` files under [androidTestSourceRootDirs]. */
fun androidTestJavaSourceFiles(module: Module): List<Path> = filesUnder(androidTestSourceRootDirs(module), ".java")
fun androidTestKotlinSourceFiles(module: Module): List<Path> = filesUnder(androidTestSourceRootDirs(module), ".kt")

/** A module's test source roots (SOURCE and GENERATED roots of its test source sets) that exist on disk. */
fun testSourceRootDirs(module: Module): List<Path> = collapseNestedRoots(
    module.sourceSets.filter(::isTestSourceSet)
        .flatMap { it.contentRoots }
        .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
        .map { Paths.get(it.dir.path) },
).filter { Files.isDirectory(it) }

/** The `.java` files under [testSourceRootDirs]. */
fun testJavaSourceFiles(module: Module): List<Path> = filesUnder(testSourceRootDirs(module), ".java")

/** The `.kt` files under [testSourceRootDirs]. */
fun testKotlinSourceFiles(module: Module): List<Path> = filesUnder(testSourceRootDirs(module), ".kt")

/** Whether [module] has any test source to compile. */
fun hasTestSources(module: Module): Boolean =
    testSourceRootDirs(module).any { root -> filesUnder(listOf(root), ".kt").isNotEmpty() || filesUnder(listOf(root), ".java").isNotEmpty() }

/** The RESOURCE roots of the test source sets. */
fun testResourceRoots(module: Module): List<Path> = module.sourceSets.filter(::isTestSourceSet)
    .flatMap { it.contentRoots }.filter { ContentRole.RESOURCE in it.roles }.map { Paths.get(it.dir.path) }

/** Java test classes, beside the main `classes`. */
fun testOutputDir(module: Module): Path = outputDir(module).resolveSibling("test-classes")

/** Kotlin test classes, beside the main `kotlin-classes`. */
fun testKotlinOutputDir(module: Module): Path = outputDir(module).resolveSibling("test-kotlin-classes")

/** Test resources, beside the main `resources`. */
fun testResourcesDir(module: Module): Path = outputDir(module).resolveSibling("test-resources")

/** Where a test run writes the runner class it generates. */
fun testRunnerDir(module: Module): Path = outputDir(module).resolveSibling("test-runner")

/** The test compile and run outputs, Kotlin first (Java test sources compile against it). */
fun testOutputs(module: Module): List<Path> = listOf(testKotlinOutputDir(module), testOutputDir(module), testResourcesDir(module))

/** What test sources compile against: the module's own main output, then its test-scoped classpath (module
 *  outputs with their Kotlin siblings, and libraries). */
fun testCompileClasspath(module: Module): List<Path> {
    val entries = module.classpath(DependencyScope.TEST_IMPLEMENTATION).entries
    val modules = entries.filter { it.kind == ClasspathEntryKind.MODULE_OUTPUT }.map { Paths.get(it.root.path) }
    val libs = entries.filter { it.kind == ClasspathEntryKind.LIBRARY }.map { Paths.get(it.root.path) }
    return (classOutputs(module) + modules + kotlinSiblings(modules) + libs).distinct()
}

/**
 * What a test run executes against: the test outputs, the module's own output, and the union of the runtime and
 * test classpaths (the runtime closure carries what production code needs at run time, the test classpath what the
 * tests declared), existing entries only.
 */
fun testRuntimeClasspath(module: Module): List<Path> {
    val runtime = module.classpath(DependencyScope.RUNTIME_ONLY).entries.map { Paths.get(it.root.path) }
    val test = module.classpath(DependencyScope.TEST_IMPLEMENTATION).entries.map { Paths.get(it.root.path) }
    return (testOutputs(module) + classOutputs(module) + resourcesDir(module) + runtime + test + kotlinSiblings(runtime + test))
        .filter { Files.isDirectory(it) || Files.isRegularFile(it) }
        .distinct()
}

/** Modules a test compilation needs built first: `testImplementation project(…)` dependencies, which
 *  [directModuleDeps] leaves out of the main graph on purpose. */
fun testModuleDeps(module: Module, byId: Map<ModuleId, Module>): List<Module> =
    module.dependencies.filterIsInstance<ModuleDependency>()
        .filter { it.scope.onTest && !it.scope.onCompile && !it.scope.onRuntime }
        .mapNotNull { byId[it.target] }

private fun filesUnder(roots: List<Path>, suffix: String): List<Path> = roots.flatMap { root ->
    Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(suffix) }.collect(Collectors.toList()) }
}
