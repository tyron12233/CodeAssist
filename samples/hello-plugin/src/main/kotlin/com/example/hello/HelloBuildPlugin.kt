package com.example.hello

import dev.ide.build.BuildConfiguration
import dev.ide.build.BuildContext
import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildGoal
import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildLogLevel
import dev.ide.build.BuildPlugin
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticLocation
import dev.ide.build.Lifecycle
import dev.ide.build.RunAction
import dev.ide.build.RunTaskProvider
import dev.ide.build.RunTaskSpec
import dev.ide.build.SourceGenRequest
import dev.ide.build.SourceGenResult
import dev.ide.build.SourceGenerator
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskGraph
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.model.ContentRole
import dev.ide.model.LibraryDependency
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.Project
import dev.ide.platform.ServiceLookup
import dev.ide.platform.settings.SETTINGS_ACCESS
import dev.ide.platform.settings.SettingsPage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The plugin's **build facet**: three registrations against the extension points in `build-api`, covering the
 * two things a build extension can do.
 *
 * - [HelloBuildInfoGenerator] is a [SourceGenerator] (`platform.sourceGenerator`). It emits a
 *   `hello.buildinfo.HelloBuildInfo` class into the module's generated source root before compilation, so
 *   project code can read its own module name back at runtime. Generating source is the one thing a
 *   [BuildPlugin] cannot do portably (the compile tasks are constructed with their source-root lists before a
 *   contributed plugin is applied), which is why it is this seam and not the next one.
 * - [HelloBuildReportPlugin] is a [BuildPlugin] (`platform.buildPlugin`). It registers one task per module,
 *   writes a small report under the module's build directory, and hangs that task off the module's `assemble`
 *   aggregate by name.
 * - [HelloRunTaskProvider] is a [RunTaskProvider] (`platform.runTaskProvider`). It puts a row in the Run
 *   picker that writes the same report on demand, without a full build.
 *
 * All three are registered from the engine facet ([HelloPlugin]), because they are plain extension-point
 * registrations. `docs/custom-build-plugins.md` walks through the whole surface.
 */

// ---------------------------------------------------------------------------
// Shared: where the report goes, and what it says
// ---------------------------------------------------------------------------

internal object HelloBuildReport {

    /** Used for the task name, the report directory, and the plugin id, so all three stay in step. */
    const val ID = "hello-build-report"

    /** `<module>/build/reports/hello-build-report/build-report.txt`. */
    fun fileIn(buildDir: Path): Path = buildDir.resolve("reports").resolve(ID).resolve("build-report.txt")

    /** The per-module task name. A task name is `:<module>:<task>`, matching the ids the Run picker shows. */
    fun taskName(module: Module): TaskName = TaskName(":${module.name}:helloBuildReport")

    /**
     * The report body, derived only from the model.
     *
     * Deliberately free of anything that changes on its own, a timestamp above all: the text is this task's
     * only declared input, so a value that changed every build would re-run the task (and everything
     * downstream of it) every build. The Crashlytics build id in `android-support` is stable for the same
     * reason.
     */
    fun text(module: Module, variant: String?): String = buildString {
        appendLine("module: ${module.name}")
        appendLine("type: ${module.type.id}")
        if (variant != null) appendLine("variant: $variant")
        appendLine("language level: ${module.languageLevel}")
        val roots = sourceRoots(module)
        appendLine("source roots: ${if (roots.isEmpty()) "(none)" else roots.joinToString(", ") { it.fileName.toString() }}")
        val libraries = module.dependencies.filterIsInstance<LibraryDependency>().map { it.library.name }.sorted()
        val modules = module.dependencies.filterIsInstance<ModuleDependency>().map { it.target.value }.sorted()
        appendLine("module dependencies: ${if (modules.isEmpty()) "(none)" else modules.joinToString(", ")}")
        appendLine("library dependencies: ${libraries.size}")
        libraries.forEach { appendLine("  $it") }
    }

    /** The module's hand-written source roots that exist on disk. */
    fun sourceRoots(module: Module): List<Path> = module.sourceSets
        .flatMap { it.contentRoots }
        .filter { ContentRole.SOURCE in it.roles }
        .map { Paths.get(it.dir.path) }
        .filter { Files.isDirectory(it) }
}

// ---------------------------------------------------------------------------
// The task
// ---------------------------------------------------------------------------

/**
 * Writes [HelloBuildReport.text] to [out]. The same task serves the build graph and the Run picker row, so
 * the two paths cannot drift.
 *
 * A task declares typed inputs and outputs and gets up-to-date checking, output caching, level-parallel
 * scheduling, cancellation, and console streaming from the engine. Here the whole input is the report text
 * itself, which is exactly the condition under which the output would differ.
 */
internal class HelloBuildReportTask(
    override val name: TaskName,
    private val module: Module,
    private val variant: String?,
    private val out: Path,
) : Task {

    private val body: String get() = HelloBuildReport.text(module, variant)

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply { property("report", body) }

    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply { filePath("report", out) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        return runCatching {
            // A module with no source roots on disk still builds, but the report is worth nothing, so it is
            // reported as a structured diagnostic rather than buried in the text log. The engine stamps it
            // with this task's name and the console lists it under Problems, click-to-open on the location.
            if (HelloBuildReport.sourceRoots(module).isEmpty()) {
                ctx.diagnostics.report(
                    BuildDiagnostic(
                        severity = BuildSeverity.WARNING,
                        message = "module '${module.name}' declares no source roots that exist on disk",
                        source = "hello",
                        location = DiagnosticLocation(module.outputDir.path),
                    ),
                )
            }
            Files.createDirectories(out.parent)
            Files.write(out, body.toByteArray(Charsets.UTF_8))
            // The plain transcript channel. `buildLog` is the same stream with a level attached, for the one
            // line worth colouring.
            ctx.logger()("${name.value} -> $out")
            TaskResult.Success as TaskResult
        }.getOrElse {
            ctx.buildLog.log(BuildLogEntry("${name.value} failed: ${it.message}", BuildLogLevel.ERROR))
            TaskResult.Failed("${name.value} failed: ${it.message}", it)
        }
    }
}

// ---------------------------------------------------------------------------
// platform.buildPlugin: contribute the task to every build graph
// ---------------------------------------------------------------------------

/**
 * Registers `:<module>:helloBuildReport` for every module and anchors it on the module's terminal aggregate.
 *
 * Registration is lazy: the factory runs when the container is realized, after every build system and every
 * other contributed plugin has registered. That is what lets [dev.ide.build.TaskContainer.named] wire to a
 * task this plugin does not own, in either direction, without knowing which pipeline is running.
 */
class HelloBuildReportPlugin(
    /**
     * Whether the report is wanted, read from this plugin's own settings page at the moment a graph is
     * assembled (see [helloBuildReportEnabled]). A build plugin has no settings callback to be told in, so
     * the value is read when it is needed rather than cached.
     */
    private val enabled: () -> Boolean = { true },
) : BuildPlugin {

    override val id = HelloBuildReport.ID

    /** A clean has nothing to report on, and its graph carries none of the anchors below. */
    override fun appliesTo(config: BuildConfiguration): Boolean =
        config.request.goal != BuildGoal.CLEAN && enabled()

    override fun apply(config: BuildConfiguration) {
        val variant = config.request.variant.name
        for (module in config.project.modules) {
            val task = HelloBuildReport.taskName(module)
            val out = HelloBuildReport.fileIn(config.env.buildDir(module))
            config.tasks.register(task) { HelloBuildReportTask(task, module, variant, out) }
            // The Android pipeline suffixes `assemble` with the variant it is building; the Java pipeline does
            // not. Configuring a task that no build system registered is ignored, so both spellings can be
            // named and whichever exists is the one that takes the edge.
            config.tasks.named(Lifecycle.assemble(module.name)).configure { dependsOn(task) }
            config.tasks.named(Lifecycle.assemble(module.name, variant)).configure { dependsOn(task) }
        }
    }
}

// ---------------------------------------------------------------------------
// platform.sourceGenerator: emit a source file ahead of compilation
// ---------------------------------------------------------------------------

/**
 * Generates `hello.buildinfo.HelloBuildInfo` into the module's generated source root, which the build wires
 * as a `ContentRole.GENERATED` root, so the generated file compiles and indexes like a hand-written one.
 *
 * It runs only for a module whose own sources mention the class. A generator that always applies would add a
 * file to every module of every project the IDE opens; gating on a signal from the project is what the
 * built-in generators do too (KSP activates on a directly-declared processor runtime, the Compose compiler
 * plugin on a classpath probe).
 */
class HelloBuildInfoGenerator : SourceGenerator {

    override val id = "hello-buildinfo"

    override fun appliesTo(request: SourceGenRequest): Boolean =
        (request.kotlinSources + request.javaSources).any { file ->
            runCatching { String(Files.readAllBytes(file), Charsets.UTF_8).contains(CLASS_NAME) }
                .getOrDefault(false)
        }

    override fun generate(request: SourceGenRequest): SourceGenResult {
        val kotlin = request.kotlinSources.isNotEmpty()
        val target = request.outputDir
            .resolve(PACKAGE.replace('.', '/'))
            .resolve(if (kotlin) "$CLASS_NAME.kt" else "$CLASS_NAME.java")
        return runCatching {
            Files.createDirectories(target.parent)
            val text = if (kotlin) kotlinSource(request) else javaSource(request)
            Files.write(target, text.toByteArray(Charsets.UTF_8))
            SourceGenResult(true, listOf("$id: generated $PACKAGE.$CLASS_NAME for ${request.moduleName}"))
        }.getOrElse { SourceGenResult(false, listOf("$id: $it")) }
    }

    /** As with the report, nothing here changes unless the module does: no timestamp, no build counter. */
    private fun kotlinSource(request: SourceGenRequest): String = """
        |package $PACKAGE
        |
        |/** Generated by the Hello plugin. Do not edit. */
        |object $CLASS_NAME {
        |    const val MODULE: String = "${request.moduleName}"
        |    const val SOURCE_FILE_COUNT: Int = ${request.kotlinSources.size + request.javaSources.size}
        |    val DEPENDENCIES: List<String> = listOf(
        |${request.declaredDependencies.sorted().joinToString("\n") { "|        \"$it\"," }}
        |    )
        |}
        |
    """.trimMargin()

    private fun javaSource(request: SourceGenRequest): String = """
        |package $PACKAGE;
        |
        |/** Generated by the Hello plugin. Do not edit. */
        |public final class $CLASS_NAME {
        |    public static final String MODULE = "${request.moduleName}";
        |    public static final int SOURCE_FILE_COUNT = ${request.kotlinSources.size + request.javaSources.size};
        |    public static final String[] DEPENDENCIES = {
        |${request.declaredDependencies.sorted().joinToString("\n") { "|        \"$it\"," }}
        |    };
        |
        |    private $CLASS_NAME() {}
        |}
        |
    """.trimMargin()

    private companion object {
        const val PACKAGE = "hello.buildinfo"
        const val CLASS_NAME = "HelloBuildInfo"
    }
}

// ---------------------------------------------------------------------------
// platform.runTaskProvider: a row in the Run picker
// ---------------------------------------------------------------------------

/**
 * Offers "Hello: write build report" per module and executes it.
 *
 * The id must not begin with `build:`, `run:` or `assemble:`: those prefixes are dispatched to the host's own
 * pipeline instead of coming back to [actionFor], which is how a provider can offer a row that reuses
 * existing machinery. Anything else is the provider's to execute.
 */
class HelloRunTaskProvider : RunTaskProvider {

    override fun tasksFor(module: Module): List<RunTaskSpec> = listOf(
        RunTaskSpec(id = idFor(module), label = "Hello: write build report (${module.name})", group = "build"),
    )

    override fun actionFor(spec: RunTaskSpec, project: Project, module: Module, ctx: BuildContext): RunAction? {
        if (spec.id != idFor(module)) return null   // not this provider's row
        val out = HelloBuildReport.fileIn(ctx.env.buildDir(module))
        // No variant: this row is not building anything, so there is nothing selected to report.
        val task = HelloBuildReportTask(HelloBuildReport.taskName(module), module, variant = null, out = out)
        return RunAction(
            header = "Hello build report: ${module.name}",
            graph = SingleTaskGraph(task),
            onSuccess = { log -> log("report written to $out") },
        )
    }

    private fun idFor(module: Module) = "hello:buildReport:${module.name}"
}

// ---------------------------------------------------------------------------
// A one-task graph
// ---------------------------------------------------------------------------

/**
 * A [TaskGraph] of one task, for a [RunAction] that needs no dependencies. The host runs it through the same
 * executor, console and cancellation path as a built-in task.
 *
 * `TaskInputsImpl` and `TaskOutputsImpl` above come from build-api, so the input/output contracts do not have
 * to be reimplemented here; a graph this small is less code than reaching for the engine's `TaskGraphImpl`,
 * which exists for real dependency inference and cycle detection.
 */
internal class SingleTaskGraph(private val task: Task) : TaskGraph {
    override val tasks: List<Task> = listOf(task)
    override fun dependencies(t: Task): List<Task> = emptyList()
    override fun topologicalLevels(): List<List<Task>> = listOf(tasks)
}

// ---------------------------------------------------------------------------
// Reading the plugin's own settings at build time
// ---------------------------------------------------------------------------

/**
 * Whether the "Write a build report" toggle on [page] is on.
 *
 * [SETTINGS_ACCESS] is the seam for reading a page outside its own `onChanged`/`onAction` callbacks: a build
 * plugin is asked whether it applies whenever a graph is assembled, which is nowhere near a settings
 * interaction. It is resolved on each call, not held, so a value the user changes takes effect on the next
 * build; and a host that wired no container simply answers null, hence the default.
 */
internal fun helloBuildReportEnabled(services: ServiceLookup, page: SettingsPage): Boolean =
    services.getServiceOrNull(SETTINGS_ACCESS)?.reader(page)?.bool(HelloSettings.BUILD_REPORT, true) ?: true

/** Control keys of the plugin's settings page, shared with the page that declares them. */
internal object HelloSettings {
    const val BUILD_REPORT = "buildReport"
}
