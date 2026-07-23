package dev.ide.build.jvm

import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.build.SyncResult
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskGraph
import dev.ide.build.TaskName
import dev.ide.build.engine.DefaultTaskContainer
import dev.ide.build.engine.InterpretExecTask
import dev.ide.build.engine.ProgramInterpreter
import dev.ide.build.engine.ProgramIo
import dev.ide.build.SourceGenerator
import dev.ide.build.engine.SimpleBuildConfiguration
import dev.ide.build.engine.classOutputs
import dev.ide.build.engine.kotlinSiblings
import dev.ide.build.engine.moduleClosure
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
 * host-specific input is [bootClasspathFor] — the module's platform SDK, resolved **per module** so a
 * Java/Kotlin console module compiles against the core-Java platform (never `android.jar`); empty → the host
 * JRE (desktop), a jar → the on-device platform. [kotlin] is the workspace's incremental Kotlin compiler.
 */
class JavaBuildSystem(
    private val bootClasspathFor: (Module) -> List<Path> = { emptyList() },
    private val kotlin: IncrementalKotlinCompiler? = null,
    /** Kotlin compiler plugins applied per module (the `platform.kotlinCompilerPlugin` EP contents;
     *  defaults to the built-ins). */
    private val plugins: List<KotlinCompilerPlugin> = BUILTIN_KOTLIN_COMPILER_PLUGINS,
    /** Build-time source generators (the `platform.sourceGenerator` EP contents), run into a module's
     *  `ContentRole.GENERATED` root ahead of compilation. Empty by default. */
    private val generators: List<SourceGenerator> = emptyList(),
    /** The module's runnable entry point → the packaged jar's manifest `Main-Class` (so the built jar runs
     *  standalone). Null / blank for a library module. The host wires this to its main-class detection. */
    private val mainClassFor: (Module) -> String? = { null },
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
        JavaPlugin(bootClasspathFor, kotlin, plugins, generators, mainClassFor).apply(SimpleBuildConfiguration(project, request, tasks))
        return tasks.build()
    }

    /**
     * Build the closure of [module] then run [mainClass] as a console app by INTERPRETING its bytecode on the
     * bytecode VM — the single console-run path (the desktop `java`-fork and the on-device dex-run graphs are
     * gone). The `run` task depends on the module's `classes` (which transitively compiles the module + its
     * dependencies) and hands the runtime classpath (class dirs + library jars, no dexing) to the injected
     * [interpreter], which reads and interprets the user's/libraries' classes and bridges only the platform.
     */
    fun createInterpretRunGraph(
        project: Project,
        module: Module,
        mainClass: String,
        interpreter: ProgramInterpreter,
        programArgs: List<String> = emptyList(),
        programIo: ProgramIo? = null,
        /** True when [mainClass]'s `main` is an instance method (the interpreter constructs the class first). */
        instanceMain: Boolean = false,
    ): TaskGraph {
        val byId = project.modules.associateBy { it.id }
        val tasks = DefaultTaskContainer()
        val java = JavaPlugin(bootClasspathFor, kotlin, plugins, generators)
        for (m in moduleClosure(listOf(module.id), byId)) java.registerModule(tasks, m, byId, withJar = false)
        val runName = TaskName(":${module.name}:run")
        tasks.register(runName) {
            InterpretExecTask(runName, mainClass, { runtimeClasspath(module) }, interpreter, programArgs, instanceMain, programIo)
        }.configure { dependsOn(TaskName(":${module.name}:classes")) }
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
