package dev.ide.build.engine

import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.build.SyncResult
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskGraph
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.model.BuildSystemId
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.platform.ProgressReporter
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipInputStream
import java.nio.file.Paths

/** The compile step, injected so the engine stays decoupled from any backend (JDT wires it in). */
fun interface JavaCompile {
    fun compile(sources: List<Path>, classpath: List<Path>, outputDir: Path, sourceLevel: String): JavaCompileResult
}

data class JavaCompileResult(val success: Boolean, val messages: List<String> = emptyList())

/**
 * The native, Gradle-free Java build system: turns the module graph into a task DAG of `compileJava`
 * (and, for assemble/package goals, `jar`) tasks, wired by module dependencies. Compilation classpaths
 * use upstream module **outputs** + library jars (api/implementation export rules come straight from
 * `Module.classpath`); each `compileJava` depends on its dependencies' `compileJava`.
 */
class JavaBuildSystem(
    private val javaCompile: JavaCompile,
    private val kotlinCompile: KotlinCompile? = null,
) : BuildSystem {

    override val id: BuildSystemId = BuildSystemId.NATIVE

    override suspend fun sync(project: Project, progress: ProgressReporter): SyncResult = SyncResult(true, emptyList())

    override fun supports(moduleType: ModuleType): Boolean = moduleType.id.startsWith("java")

    override fun tasks(project: Project): List<TaskDescriptor> = listOf(
        TaskDescriptor("assemble", "build", "Compile and package all modules"),
        TaskDescriptor("compileJava", "build", "Compile Java sources"),
    )

    override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph {
        val tasks = DefaultTaskContainer()
        JavaPlugin(javaCompile, kotlinCompile).apply(SimpleBuildConfiguration(project, request, tasks))
        return tasks.build()
    }

    /**
     * Build the closure of [module] then run [mainClass] as a console app — the equivalent of Gradle's
     * `application` plugin `run` task. The `run` task depends on the module's `classes` (which transitively
     * compiles the module + its dependencies), and launches on the runtime classpath.
     */
    fun createRunGraph(
        project: Project,
        module: Module,
        mainClass: String,
        programArgs: List<String> = emptyList(),
        javaLauncher: () -> Path = { Paths.get(System.getProperty("java.home"), "bin", "java") },
    ): TaskGraph {
        val byId = project.modules.associateBy { it.id }
        val tasks = DefaultTaskContainer()
        val java = JavaPlugin(javaCompile, kotlinCompile)
        for (m in moduleClosure(listOf(module.id), byId)) java.registerModule(tasks, m, byId, withJar = false)
        val runName = TaskName(":${module.name}:run")
        tasks.register(runName) { JavaExecTask(runName, mainClass, { runtimeClasspath(module) }, programArgs, javaLauncher) }
            .configure { dependsOn(TaskName(":${module.name}:classes")) }
        return tasks.build()
    }

    /**
     * Build the closure of [module], **dex** its runtime classpath, then run [mainClass] on ART — the
     * on-device counterpart to [createRunGraph] (there is no `java` to fork on a device). The graph is
     * `compileJava* → :module:dexRun → :module:runDex`; [dexBackend] (D8 in-process) and [dexRunner]
     * (a `DexClassLoader` launcher) are injected by the host. [minApi] is the device's API level.
     */
    fun createDexRunGraph(
        project: Project,
        module: Module,
        mainClass: String,
        minApi: Int,
        dexBackend: DexBackend,
        dexRunner: DexRunner,
        programArgs: List<String> = emptyList(),
    ): TaskGraph {
        val byId = project.modules.associateBy { it.id }
        val tasks = DefaultTaskContainer()
        val java = JavaPlugin(javaCompile, kotlinCompile)
        for (m in moduleClosure(listOf(module.id), byId)) java.registerModule(tasks, m, byId, withJar = false)
        val base = outputDir(module).resolveSibling("dex-run")
        val dexName = TaskName(":${module.name}:dexRun")
        tasks.register(dexName) {
            JavaDexTask(dexName, { runtimeClasspath(module) }, minApi, base.resolve("staging"), base.resolve("dex"), dexBackend)
        }.configure { dependsOn(TaskName(":${module.name}:classes")) }
        val runName = TaskName(":${module.name}:runDex")
        tasks.register(runName) { DexExecTask(runName, mainClass, base.resolve("dex"), programArgs, dexRunner) }
            .configure { dependsOn(dexName) }
        return tasks.build()
    }

    /** Runtime classpath for running [module]: its own output (Java + Kotlin) + the full runtime dependency
     *  closure (each module output paired with its sibling `kotlin-classes`, present only for Kotlin modules). */
    private fun runtimeClasspath(module: Module): List<Path> {
        val depEntries = module.classpath(DependencyScope.RUNTIME_ONLY).entries.map { Paths.get(it.root.path) }
        return (classOutputs(module) + depEntries + kotlinSiblings(depEntries))
            .filter { Files.isDirectory(it) || Files.isRegularFile(it) }
            .distinct()
    }
}

internal class JavaCompileTask(
    private val module: Module,
    override val name: TaskName,
    private val compile: JavaCompile,
    private val ownKotlinOut: Boolean = false,   // put this module's kotlin-classes on the Java classpath
) : Task {
    /** Upstream Kotlin output dirs + (when the module compiles Kotlin) its own — so Java sees Kotlin types. */
    private fun kotlinClasspath(): List<Path> =
        (kotlinSiblings(depOutputDirs(module)) + if (ownKotlinOut) listOf(kotlinOutputDir(module)) else emptyList())
            .filter { Files.isDirectory(it) }

    private fun classpath(): List<Path> = depOutputDirs(module) + kotlinClasspath() + libJars(module)

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("sources", sourceFiles(module))
            dirPaths("deps", depOutputDirs(module) + kotlinClasspath()) // content of upstream/own outputs → re-run propagates
            filePaths("libs", libJars(module))
            property("level", levelOf(module.languageLevel))
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply { dirPath("classes", outputDir(module)) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val sources = sourceFiles(module)
        if (sources.isEmpty()) { Files.createDirectories(outputDir(module)); return TaskResult.Success }
        val result = compile.compile(sources, classpath(), outputDir(module), levelOf(module.languageLevel))
        ctx.logger()(":${module.name}:compileJava ${if (result.success) "OK" else "FAILED"}")
        return if (result.success) TaskResult.Success
        else TaskResult.Failed(result.messages.joinToString("\n").ifBlank { "compilation failed" })
    }
}

internal fun sourceFiles(module: Module): List<Path> = module.sourceSets
    .flatMap { it.contentRoots }
    .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
    .map { Paths.get(it.dir.path) }
    .filter { Files.isDirectory(it) }
    .flatMap { root -> Files.walk(root).use { s -> s.filter { it.toString().endsWith(".java") }.toList() } }

internal fun depOutputDirs(module: Module): List<Path> =
    module.classpath(DependencyScope.IMPLEMENTATION).entries
        .filter { it.kind == ClasspathEntryKind.MODULE_OUTPUT }.map { Paths.get(it.root.path) }

internal fun libJars(module: Module): List<Path> =
    module.classpath(DependencyScope.IMPLEMENTATION).entries
        .filter { it.kind == ClasspathEntryKind.LIBRARY }.map { Paths.get(it.root.path) }

internal fun outputDir(module: Module): Path = Paths.get(module.outputDir.path)

/** Convention path of a module's `jar` artifact (`build/libs/<name>.jar`, Gradle-style) — shared so other
 *  plugins (Android) can consume it by the same path. */
fun jarPath(module: Module): Path =
    Paths.get(module.outputDir.path).resolveSibling("libs").resolve("${module.name}.jar")

internal fun levelOf(level: LanguageLevel): String = when (level) {
    LanguageLevel.JAVA_8 -> "8"
    LanguageLevel.JAVA_11 -> "11"
    LanguageLevel.JAVA_17 -> "17"
    LanguageLevel.JAVA_21 -> "21"
}

/** Copy [src] jar to [dest], rewriting each entry's bytes via [transform] — used by the dex-run path to
 *  instrument library jars (exit + sandbox guards). Directory entries and duplicates are dropped. */
internal fun copyJarTransformed(src: Path, dest: Path, transform: (String, ByteArray) -> ByteArray) {
    dest.parent?.let { Files.createDirectories(it) }
    val seen = HashSet<String>()
    ZipInputStream(Files.newInputStream(src)).use { zis ->
        JarOutputStream(Files.newOutputStream(dest)).use { jos ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && seen.add(e.name)) {
                    val out = transform(e.name, zis.readBytes())
                    jos.putNextEntry(JarEntry(e.name)); jos.write(out); jos.closeEntry()
                }
                e = zis.nextEntry
            }
        }
    }
}

/** Jar [classesDir], optionally rewriting each entry's bytes via [transform] (entryName, bytes) — used by
 *  the dex-run path to instrument `System.exit`. The default identity transform is plain jarring. */
internal fun writeJar(classesDir: Path, jarPath: Path, transform: (String, ByteArray) -> ByteArray = { _, b -> b }) =
    writeJar(listOf(classesDir), jarPath, transform)

/** Jar one or more [classesDirs] (Java + Kotlin output) into [jarPath]. Later dirs win on a name clash; each
 *  entry's bytes pass through [transform]. Directory entries and duplicate names are dropped. */
internal fun writeJar(classesDirs: List<Path>, jarPath: Path, transform: (String, ByteArray) -> ByteArray = { _, b -> b }) {
    jarPath.parent?.let { Files.createDirectories(it) }
    val seen = HashSet<String>()
    JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
        for (dir in classesDirs.filter { Files.isDirectory(it) }) {
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.sorted().forEach { f ->
                    val name = dir.relativize(f).toString().replace('\\', '/')
                    if (!seen.add(name)) return@forEach
                    jos.putNextEntry(JarEntry(name))
                    jos.write(transform(name, Files.readAllBytes(f)))
                    jos.closeEntry()
                }
            }
        }
    }
}
