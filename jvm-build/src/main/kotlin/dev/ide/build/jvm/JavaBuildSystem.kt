package dev.ide.build.jvm

import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.build.SyncResult
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskGraph
import dev.ide.build.TaskName
import dev.ide.build.engine.DefaultTaskContainer
import dev.ide.build.engine.DexExecTask
import dev.ide.build.engine.DexRunner
import dev.ide.build.engine.JavaDexTask
import dev.ide.build.engine.JavaExecTask
import dev.ide.build.engine.ProgramIo
import dev.ide.build.engine.RunDexBackend
import dev.ide.build.SourceGenerator
import dev.ide.build.engine.SimpleBuildConfiguration
import dev.ide.build.engine.classOutputs
import dev.ide.build.engine.kotlinSiblings
import dev.ide.build.engine.moduleClosure
import dev.ide.build.engine.outputDir
import dev.ide.lang.kotlin.compile.BUILTIN_KOTLIN_COMPILER_PLUGINS
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
import dev.ide.lang.kotlin.compile.KotlinCompilerPlugin
import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.platform.ProgressReporter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The native, Gradle-free Java/Kotlin build system: turns the module graph into a task DAG of `compileJava`
 * (and, for assemble/package goals, `jar`) tasks, wired by module dependencies. Compilation classpaths use
 * upstream module **outputs** + library jars (api/implementation export rules come straight from
 * `Module.classpath`); each `compileJava` depends on its dependencies' `compileJava`.
 *
 * The compile steps are owned by the language modules: [JavaPlugin] wires in lang-jdt's `JdtCompileTask`
 * and (for modules with `.kt`) lang-kotlin's `KotlinCompileTask`, which drive ecj / K2 directly. The only
 * host-specific input is [bootClasspath] (empty on the desktop → the host JRE; `android.jar` + desugar
 * stubs on ART); [kotlin] is the workspace's incremental Kotlin compiler, absent for Java-only setups.
 */
class JavaBuildSystem(
    private val bootClasspath: List<Path> = emptyList(),
    private val kotlin: IncrementalKotlinCompiler? = null,
    /** Kotlin compiler plugins applied per module (the `platform.kotlinCompilerPlugin` EP contents;
     *  defaults to the built-ins). */
    private val plugins: List<KotlinCompilerPlugin> = BUILTIN_KOTLIN_COMPILER_PLUGINS,
    /** Build-time source generators (the `platform.sourceGenerator` EP contents), run into a module's
     *  `ContentRole.GENERATED` root ahead of compilation. Empty by default. */
    private val generators: List<SourceGenerator> = emptyList(),
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
        JavaPlugin(bootClasspath, kotlin, plugins, generators).apply(SimpleBuildConfiguration(project, request, tasks))
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
        programIo: ProgramIo? = null,
        /** True when [mainClass]'s `main` is an instance method (run via the reflective launcher). */
        instanceMain: Boolean = false,
    ): TaskGraph {
        val byId = project.modules.associateBy { it.id }
        val tasks = DefaultTaskContainer()
        val java = JavaPlugin(bootClasspath, kotlin, plugins, generators)
        for (m in moduleClosure(listOf(module.id), byId)) java.registerModule(tasks, m, byId, withJar = false)
        val runName = TaskName(":${module.name}:run")
        tasks.register(runName) { JavaExecTask(runName, mainClass, { runtimeClasspath(module) }, programArgs, javaLauncher, programIo, instanceMain) }
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
        dexBackend: RunDexBackend,
        dexRunner: DexRunner,
        programArgs: List<String> = emptyList(),
        programIo: ProgramIo? = null,
    ): TaskGraph {
        val byId = project.modules.associateBy { it.id }
        val tasks = DefaultTaskContainer()
        val java = JavaPlugin(bootClasspath, kotlin, plugins, generators)
        for (m in moduleClosure(listOf(module.id), byId)) java.registerModule(tasks, m, byId, withJar = false)
        val base = outputDir(module).resolveSibling("dex-run")
        val dexName = TaskName(":${module.name}:dexRun")
        // A runner that runs the program out-of-process (forked dalvikvm) is isolated by the process boundary,
        // so its dex is left un-instrumented (no ExitGuard/SandboxGuard); the in-process DexClassLoader runner
        // needs the guards to protect the host, so its dex is instrumented.
        val guarded = !dexRunner.isolatedProcess
        tasks.register(dexName) {
            JavaDexTask(dexName, { runtimeClasspath(module) }, minApi, base.resolve("staging"), base.resolve("dex"), dexBackend, guarded)
        }.configure { dependsOn(TaskName(":${module.name}:classes")) }
        val runName = TaskName(":${module.name}:runDex")
        tasks.register(runName) { DexExecTask(runName, mainClass, base.resolve("dex"), programArgs, dexRunner, programIo) }
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
