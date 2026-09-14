package dev.ide.build.engine

import dev.ide.build.BuildConfiguration
import dev.ide.build.BuildEnv
import dev.ide.build.BuildPlugin
import dev.ide.build.BuildRequest
import dev.ide.build.Task
import dev.ide.build.TaskContainer
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.Project
import dev.ide.platform.log.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** A plain [BuildConfiguration] for a plugin to contribute tasks to (the host realizes [tasks] afterwards). */
class SimpleBuildConfiguration(
    override val project: Project,
    override val request: BuildRequest,
    override val tasks: TaskContainer,
    override val buildSystemId: BuildSystemId = BuildSystemId.NATIVE,
    override val env: BuildEnv = BuildEnv.of(project),
) : BuildConfiguration

/**
 * The host's [BuildEnv]: the open workspace root, the shared cache root, and the per-module platform
 * classpath resolver the build systems already carry (`bootClasspathFor`).
 */
class DefaultBuildEnv(
    override val workspaceRoot: Path,
    override val sharedCachesRoot: Path? = null,
    private val bootClasspathFor: (Module) -> List<Path> = { emptyList() },
) : BuildEnv {
    override fun bootClasspath(module: Module): List<Path> = bootClasspathFor(module)
}

private val log = Log.logger("build.plugins")

/**
 * Apply the contributed [BuildPlugin]s that claim [config] to it, in registration order. A plugin that
 * throws is skipped rather than failing the graph: one bad extension must not make a project unbuildable.
 * Called by each build system after its own plugins, before realizing the container.
 *
 * The reason is always logged (a silently skipped extension is indistinguishable from one that ran and did
 * nothing, which is the hardest kind of plugin bug to find) and also handed to [onError], which build systems
 * wire to [dev.ide.build.BuildContext.onExtensionError] so it reaches the build console as well.
 */
fun applyBuildPlugins(
    config: BuildConfiguration,
    plugins: List<BuildPlugin>,
    onError: (String) -> Unit = {},
) {
    fun skipped(plugin: BuildPlugin, stage: String, t: Throwable) {
        val message = "build plugin '${plugin.id}' failed in $stage, skipped: ${t.message ?: t.toString()}"
        log.warn(message, t)
        runCatching { onError(message) }
    }
    for (plugin in plugins) {
        val claims = runCatching { plugin.appliesTo(config) }
            .getOrElse { skipped(plugin, "appliesTo", it); false }
        if (!claims) continue
        runCatching { plugin.apply(config) }.onFailure { skipped(plugin, "apply", it) }
    }
}

/**
 * A module's compiled-class output dirs — the Java output and the Kotlin output. Packaged together
 * (jar/dex) and tracked together (the `classes` lifecycle).
 *
 * Both are listed unconditionally rather than probing for Kotlin sources. This is called while the graph is
 * being built, before any generator has run, so a module whose Kotlin is *generated* (a Compose resources
 * `Res` class, a KSP processor's output) still has no `.kt` at that moment — and leaving its Kotlin output
 * out here would silently package a jar without the generated classes. Consumers all tolerate a directory
 * that does not exist: `writeJar` skips it, the classpath helpers filter it out.
 */
fun classOutputs(module: Module): List<Path> = listOf(outputDir(module), kotlinOutputDir(module))

/** Targets plus their transitive module dependencies (all modules when [targets] is empty). */
fun moduleClosure(targets: List<ModuleId>, byId: Map<ModuleId, Module>): List<Module> {
    val out = LinkedHashMap<ModuleId, Module>()
    fun visit(id: ModuleId) {
        if (id in out) return
        val m = byId[id] ?: return
        out[id] = m
        m.dependencies.filterIsInstance<ModuleDependency>().filter { buildsBefore(it) }.forEach { visit(it.target) }
    }
    (if (targets.isEmpty()) byId.keys.toList() else targets).forEach { visit(it) }
    return out.values.toList()
}

fun directModuleDeps(module: Module, byId: Map<ModuleId, Module>): List<Module> =
    module.dependencies.filterIsInstance<ModuleDependency>()
        .filter { buildsBefore(it) }
        .mapNotNull { byId[it.target] }

/**
 * Does [dependency] have to be built before the module declaring it?
 *
 * Only if it reaches that module's compile or runtime classpath. A **test-only** module dependency
 * (Gradle's `testImplementation project(":fixtures")`) reaches neither: a CodeAssist module is one
 * compilation, and the test sources that would consume it are not part of it.
 *
 * Keeping such an edge is not merely redundant, it makes correct projects unbuildable. `testImplementation`
 * cycles are normal and deliberate — a shared test-fixtures module depends on the production module whose
 * fixtures it provides, and that module's own tests depend back on the fixtures — because in Gradle the
 * edge runs test→main and never main→main. Treated as a compile edge it closes a loop, and the build fails
 * to configure with a cyclic-dependency error naming modules that do not actually depend on each other.
 * (This is the shape of CodeAssist's own `:test-support`.)
 *
 * An off-classpath scope ([DependencyScope.NATIVES]) is excluded for the same reason: nothing in it is
 * compiled or run against.
 */
private fun buildsBefore(dependency: ModuleDependency): Boolean =
    dependency.scope.onCompile || dependency.scope.onRuntime

/** The JVM resource roots packaged into the module's output. Test-only source sets contribute none, for
 *  the same reason they contribute no sources (see `sourceRootDirs`). */
fun resourceRoots(module: Module): List<Path> = module.sourceSets
    .filter { it.scope.onCompile || it.scope.onRuntime }
    .flatMap { it.contentRoots }.filter { ContentRole.RESOURCE in it.roles }.map { Paths.get(it.dir.path) }

fun resourcesDir(module: Module): Path = outputDir(module).resolveSibling("resources")

/** `processResources`: copy a module's JVM resource roots into the packaged output. No resource roots ⇒
 *  it declares no inputs, so the engine reports it NO-SOURCE (skipped), exactly like Gradle. */
class ProcessResourcesTask(
    override val name: TaskName,
    private val resourceRoots: List<Path>,
    private val outDir: Path,
) : Task {
    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        if (resourceRoots.isNotEmpty()) dirPaths("resources", resourceRoots)
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("out", outDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        return runCatching {
            Files.createDirectories(outDir)
            for (root in resourceRoots.filter { Files.isDirectory(it) }) {
                Files.walk(root).use { s ->
                    s.filter { Files.isRegularFile(it) }.forEach { src ->
                        val dest = outDir.resolve(root.relativize(src))
                        Files.createDirectories(dest.parent)
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            TaskResult.Success as TaskResult
        }.getOrElse { TaskResult.Failed("processResources failed: ${it.message}", it) }
    }
}

/**
 * A no-action **lifecycle aggregate** (Gradle's `classes`, `assemble`, …). It does no work; it groups its
 * dependencies and reports UP-TO-DATE while the artifacts it fronts ([trackedDirs]/[trackedFiles]) are
 * unchanged. A `property` keeps its inputs non-empty so it is an up-to-date no-op, not NO-SOURCE.
 */
class LifecycleTask(
    override val name: TaskName,
    private val trackedDirs: List<Path> = emptyList(),
    private val trackedFiles: List<Path> = emptyList(),
) : Task {
    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        if (trackedDirs.isNotEmpty()) dirPaths("dirs", trackedDirs)
        if (trackedFiles.isNotEmpty()) filePaths("files", trackedFiles)
        property("lifecycle", name.value)
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl()
    // A lifecycle task does nothing but gather its dependencies; the engine's own "> Task :x" banner is
    // already the whole story, so it adds no line of its own.
    override suspend fun execute(ctx: TaskContext): TaskResult = TaskResult.Success
}

/** `jar`: package one or more classes directories (Java + Kotlin output) into [outJar] — a module's
 *  published artifact. */
class JarTask(
    override val name: TaskName,
    private val classesDirs: List<Path>,
    private val outJar: Path,
    /** Resolves the manifest `Main-Class` (the module's runnable entry point), so the jar runs standalone
     *  (`java -jar`). Evaluated at execution time (the entry point may be detected from now-compiled sources);
     *  null / blank → a manifest with no `Main-Class` (a plain library jar). */
    private val mainClass: () -> String? = { null },
) : Task {
    constructor(name: TaskName, classesDir: Path, outJar: Path) : this(name, listOf(classesDir), outJar)

    override val inputs: TaskInputs get() = TaskInputsImpl().apply {
        dirPaths("classes", classesDirs)
        // Part of the fingerprint so a changed entry point (or a main-class override) re-jars with the new
        // Main-Class instead of being skipped as up-to-date. Detection is best-effort here.
        property("mainClass", runCatching { mainClass() }.getOrNull().orEmpty())
    }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("jar", outJar) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        return runCatching {
            writeJar(classesDirs, outJar, mainClass())
            ctx.debug("${name.value} -> ${outJar.fileName}")
            TaskResult.Success as TaskResult
        }.getOrElse { TaskResult.Failed("jar failed: ${it.message}", it) }
    }
}
