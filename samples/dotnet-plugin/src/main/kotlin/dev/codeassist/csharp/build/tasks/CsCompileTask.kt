package dev.codeassist.csharp.build.tasks

import android.util.Log
import dev.codeassist.csharp.editor.diagnostics.CsDiagnostic
import dev.codeassist.csharp.editor.diagnostics.CsDiagnosticTags
import dev.codeassist.csharp.editor.diagnostics.CsDiagnostics
import dev.codeassist.csharp.project.CsMains
import dev.codeassist.csharp.project.CsProject
import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.project.csproj.CsprojGraph
import dev.codeassist.csharp.project.csproj.CsprojExtras
import dev.codeassist.csharp.project.csproj.CsprojInfo
import dev.codeassist.csharp.sdk.CsChecker
import dev.codeassist.csharp.sdk.CsCompilerHost
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.codeassist.csharp.sdk.RUNTIME_CONFIG
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.ToolCancellation
import dev.codeassist.csharp.util.killableTool
import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticKind
import dev.ide.build.DiagnosticLocation
import dev.ide.build.DiagnosticSink
import dev.ide.build.RunConsole
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Compiles the project's sources to [dll] with Roslyn.
 *
 * Compiler output is sent to the Run [console] and the build log. This ensures that a Run which stops
 * at a compile error does not leave the console showing "(no output)".
 *
 * Roslyn's output is also parsed into editor diagnostics (see `CsDiagnostics`) and reported as structured
 * build diagnostics. This supplies the Problems tab with messages that can be opened at their source locations.
 *
 * `csc` stops after the syntax stage, so a syntax error in one file can prevent it from reporting errors
 * in other files. After a failed compile, [CsChecker] is asked for all diagnostics. The Problems tab and
 * console receive those additional diagnostics, while the DLL and exit code remain those from `csc`.
 *
 * Warnings follow the editor's diagnostic rules: they are not sent to the console, and only warnings in
 * [PROBLEM_WARNINGS] are reported to the Problems tab. Errors are sent to both.
 */
internal class CsCompileTask(
    override val name: TaskName,
    private val dir: Path,
    private val dll: Path,
    private val sdk: DotnetSdk,
    private val console: RunConsole?,
    private val mainClass: String? = null,
    /**
     * False for a project being built because another references it: the projects it needs in turn were
     * built before it, and it is left alone when its DLL is newer than everything it is made from.
     */
    private val root: Boolean = true,
) : Task {

    /** The folder of the project file, which is [dir] unless the module keeps its project in a subfolder. */
    private val projectDir: Path get() = Csproj.find(dir)?.parent ?: dir

    /** The projects this one references, in build order, or null when it has no project file. */
    private fun referencedProjects(project: CsprojInfo?): CsprojGraph.Result? {
        val file = Csproj.find(dir) ?: return null
        if (project == null || project.projectReferences.isEmpty()) return null
        return CsprojGraph.referenced(file, project)
    }

    // Task inputs and outputs

    /**
     * Declares the inputs that determine whether the project needs recompilation: source files, the project file,
     * referenced DLLs, and the runtime tree used for compilation.
     */
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            val project = Csproj.load(dir)
            val referenced = referencedProjects(project)?.nodes.orEmpty()
            filePaths("sources", CsprojGraph.sources(projectDir, project))
            filePaths("project", listOfNotNull(Csproj.find(dir)))
            filePaths("references", project?.let { resolveReferences(it) }?.first ?: emptyList())
            // A change in a referenced project is a change to this one's build.
            filePaths("referencedSources", referenced.flatMap { CsprojGraph.sources(it.dir, it.info) })
            filePaths("referencedProjects", referenced.map { it.file })
            property("tree", DotnetSdk.VERSION)
            property("mainClass", mainClass.orEmpty())
        }

    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {
            filePath("assembly", dll)
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val status = sdk.prepare()
        if (status is DotnetSdk.Status.Unavailable) return TaskResult.Failed(status.reason)

        val launcher = sdk.launcher ?: return TaskResult.Failed(DotnetSdk.NO_DOTNET)
        val report: (String) -> Unit =
            console?.let { alsoToConsole(ctx.logger(), it) } ?: { line -> ctx.logger()(line) }

        val project = Csproj.load(dir)
        project?.let { Csproj.unsupported(it) }?.let { reason ->
            report(reason)
            ctx.diagnostics.reportProblem(reason, DiagnosticKind.DEPENDENCY)
            return TaskResult.Failed(reason)
        }

        val all = CsprojGraph.sources(projectDir, project)

        // `-main:` is incompatible with top-level statements, so exclude those files when running a chosen class.
        val leftOut = if (mainClass != null) CsMains.topLevelFiles(dir).filter { it in all } else emptyList()
        if (leftOut.isNotEmpty()) {
            report("Top-level file(s) left out of this run: ${leftOut.joinToString { it.fileName.toString() }}")
        }

        val sources = all - leftOut.toSet()
        if (sources.isEmpty()) return TaskResult.Failed("no .cs files in ${dir.fileName}")

        if (project != null && project.packageReferences.isNotEmpty()) {
            val problems = restorePackages(project, report)
            if (problems.isNotEmpty()) {
                problems.forEach { ctx.diagnostics.reportProblem(it, DiagnosticKind.DEPENDENCY) }
                return TaskResult.Failed(problems.first())
            }
        }

        val graph = referencedProjects(project)
        if (graph != null && graph.problems.isNotEmpty()) {
            graph.problems.forEach {
                report(it)
                ctx.diagnostics.reportProblem(it, DiagnosticKind.DEPENDENCY)
            }
            return TaskResult.Failed(graph.problems.first())
        }
        val nodes = graph?.nodes.orEmpty()

        if (!root && isCurrent(all, nodes)) {
            ctx.logger()("${dir.fileName} is up to date")
            return TaskResult.Success
        }

        // Referenced projects first, each after the ones it needs.
        if (root) {
            for (node in nodes) {
                report("Building ${node.assemblyName}")
                val built = CsCompileTask(
                    TaskName(":${node.assemblyName}:compileCs"), node.dir, node.output, sdk, console, root = false,
                ).execute(ctx)
                if (built !is TaskResult.Success) return built
            }
        }

        val (own, missing) = project?.let { resolveReferences(it) }
            ?: (emptyList<Path>() to emptyList<String>())

        // What a referenced project compiles against reaches this one too, as it does in an SDK build.
        val inherited = nodes.flatMap { node ->
            Csproj.resolveReferences(node.info, node.dir, sdk.nuget, sdk.frameworkDllNames()).first
        }
        val projectDlls = nodes.map { it.output }
        val referenced = (own + projectDlls + inherited).distinctBy { it.fileName.toString().lowercase() }

        if (missing.isNotEmpty()) {
            val reason = "reference not found: ${missing.joinToString()}"
            report(reason)
            ctx.diagnostics.reportProblem(reason, DiagnosticKind.DEPENDENCY)
            return TaskResult.Failed(reason)
        }

        var startedAt = 0L
        var usedResidentHost = false
        var arguments = emptyList<String>()
        val compilerLines = ArrayList<String>()

        val exitCode = killableTool { cancellation ->
            startedAt = System.nanoTime()

            prepareOutput(project)
            val usings = writeGlobalUsings(project)
            copyReferences(project, referenced, projectDlls + nodes.flatMap { copiedBy(it) })
            val assemblyInfo = writeAssemblyInfo(project)
            arguments = compilerArguments(project, sources, referenced, usings, assemblyInfo)

            val onLine: (String) -> Unit = { line ->
                Log.d(TAG, "csc> $line")
                if (!isWarning(line)) report(line)
                compilerLines += line
            }

            // Try the resident compiler first. A warm host takes about 0.1 s, compared with about 3.5 s
            // for a fresh csc process. If the host cannot be used, fall back to the regular csc process.
            val viaHost =
                if (CsCompilerHost.ENABLED) CsCompilerHost.compile(sdk, arguments, cancellation, onLine) else null

            if (viaHost != null) {
                usedResidentHost = true
                viaHost
            } else {
                runPlainCsc(launcher, arguments, cancellation, onLine)
            }
        }

        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        val summary = "csc exited with $exitCode in $tookMs ms" +
            if (usedResidentHost) " (resident compiler)" else ""

        Log.d(TAG, summary)
        ctx.logger()(summary)

        val failed = exitCode != 0 || !Files.isRegularFile(dll)
        reportProblems(ctx, compilerLines, failed, arguments, report)

        if (failed) return TaskResult.Failed("${dir.fileName} did not compile")
        return TaskResult.Success
    }

    // Diagnostic reporting

    /**
     * Sends compiler problems to the Problems tab.
     *
     * After a failed compile, [CsChecker] supplies syntax and semantic diagnostics together. Diagnostics
     * that `csc` did not print are also sent to the console. If the checker cannot be used, or compilation
     * succeeds, the Problems tab receives the compiler's own lines.
     */
    private suspend fun reportProblems(
        ctx: TaskContext,
        compilerLines: List<String>,
        failed: Boolean,
        arguments: List<String>,
        report: (String) -> Unit,
    ) {
        var problems = compilerLines

        if (failed) {
            val checked = ArrayList<String>()
            val checkerExit = killableTool { cancellation ->
                CsChecker.check(sdk, arguments, cancellation) { checked += it }
            }

            if (checkerExit != null) {
                checked.filterNot { it in compilerLines || isWarning(it) }.forEach(report)
                problems = checked
            }
        }

        problems.forEach { ctx.diagnostics.reportCompilerLine(it) }
    }

    /**
     * Reports compiler output as a structured diagnostic when it is an error or warning, whether located
     * (`path(line,col): error CS0029: ...`) or unlocated (`error CS5001: ...`).
     *
     * Other lines, such as the compiler banner, are treated as plain text.
     */
    private fun DiagnosticSink.reportCompilerLine(line: String) {
        val located = CsDiagnostics.parse(line)
        if (located != null) {
            if (located.severity == CsDiagnostic.Severity.WARNING && located.code !in PROBLEM_WARNINGS) return

            report(
                BuildDiagnostic(
                    severity = located.severity.toBuildSeverity(),
                    message = located.message,
                    kind = DiagnosticKind.COMPILER,
                    source = COMPILER_NAME,
                    location = DiagnosticLocation(located.path, located.line, located.column),
                    code = located.code,
                    detail = line,
                ),
            )
            return
        }

        val unlocated = UNLOCATED.matchEntire(line) ?: return
        val (severity, code, message) = unlocated.destructured
        if (severity == "warning" && code !in PROBLEM_WARNINGS) return

        report(
            BuildDiagnostic(
                severity = if (severity == "error") BuildSeverity.ERROR else BuildSeverity.WARNING,
                message = message,
                kind = DiagnosticKind.COMPILER,
                source = COMPILER_NAME,
                code = code,
                detail = line,
            ),
        )
    }

    /** Returns true when a compiler output line represents a warning, whether located or unlocated. */
    private fun isWarning(line: String): Boolean =
        CsDiagnostics.parse(line)?.severity == CsDiagnostic.Severity.WARNING ||
            UNLOCATED.matchEntire(line)?.groupValues?.get(1) == "warning"

    /** Reports a problem found before compilation, such as a missing reference. */
    private fun DiagnosticSink.reportProblem(message: String, kind: DiagnosticKind) {
        report(BuildDiagnostic(BuildSeverity.ERROR, message, kind, COMPILER_NAME))
    }

    private fun CsDiagnostic.Severity.toBuildSeverity(): BuildSeverity = when (this) {
        CsDiagnostic.Severity.ERROR -> BuildSeverity.ERROR
        CsDiagnostic.Severity.WARNING -> BuildSeverity.WARNING
    }

    /**
     * Creates the output folder, removes the previous DLL, and writes the runtimeconfig beside it.
     * Only executable projects need a runtimeconfig; libraries are emitted as DLLs alone.
     */
    private fun prepareOutput(project: CsprojInfo?) {
        Files.createDirectories(dll.parent)
        Files.deleteIfExists(dll)
        if (project?.isLibrary == true) return

        Files.write(
            dll.resolveSibling(dll.fileName.toString().removeSuffix(".dll") + ".runtimeconfig.json"),
            RUNTIME_CONFIG.toByteArray(Charsets.UTF_8),
        )
    }

    /** Writes the project's requested global usings under `obj/`, or returns null when there are none. */
    private fun writeGlobalUsings(project: CsprojInfo?): Path? {
        val text = Csproj.globalUsingsText(project)
        val usings = dir.resolve("obj").resolve("codeassist").resolve("GlobalUsings.g.cs")
            .takeIf { text.isNotEmpty() }

        usings?.let {
            Files.createDirectories(it.parent)
            Files.write(it, text.toByteArray(Charsets.UTF_8))
        }

        return usings
    }

    /** Writes generated assembly attributes, such as `<Version>` and `<Company>`, under `obj/`. */
    private fun writeAssemblyInfo(project: CsprojInfo?): Path? {
        val text = project?.let(CsprojExtras::assemblyInfoText).orEmpty()
        if (text.isEmpty()) return null

        val file = dir.resolve("obj").resolve("codeassist").resolve("AssemblyInfo.g.cs")
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray(Charsets.UTF_8))
        return file
    }

    // References and packages

    /** Resolves the project's explicit references and already-restored NuGet package references. */
    private fun resolveReferences(project: CsprojInfo) =
        Csproj.resolveReferences(project, dir, sdk.nuget, sdk.frameworkDllNames())

    /** Returns the DLLs from the project's cached NuGet packages. */
    private fun packageDlls(project: CsprojInfo): List<Path> =
        sdk.nuget.cached(Csproj.packageRequests(project), sdk.frameworkDllNames()).dlls

    /**
     * Restores packages that have not yet been unpacked.
     *
     * Returns any problems encountered, each of which is also sent to the console.
     */
    private suspend fun restorePackages(project: CsprojInfo, report: (String) -> Unit): List<String> {
        val requests = Csproj.packageRequests(project)
        val exclude = sdk.frameworkDllNames()
        if (sdk.nuget.isRestored(requests, exclude)) return emptyList()

        report("Restoring NuGet packages: ${project.packageReferences.joinToString { it.id }}")

        val result = killableTool { cancellation ->
            sdk.nuget.restore(requests, exclude) { cancellation.isCancelled }
        }

        result.problems.forEach(report)
        return result.problems
    }

    /**
     * Copies references not marked `<Private>false</Private>` beside the program's DLL, where the runtime
     * looks for them. When there is no project file, all references are copied.
     */
    private fun copyReferences(project: CsprojInfo?, referenced: List<Path>, fromProjects: List<Path>) {
        val toCopy = project?.let { Csproj.copyLocal(it, dir) + packageDlls(it) + fromProjects } ?: referenced

        for (reference in toCopy.distinctBy { it.fileName.toString().lowercase() }) {
            val copy = dll.parent.resolve(reference.fileName.toString())
            if (copy == reference) continue
            Files.copy(reference, copy, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** What a referenced project needs beside it at run time: its copy-local references and its packages. */
    private fun copiedBy(node: CsprojGraph.Node): List<Path> =
        Csproj.copyLocal(node.info, node.dir) +
            sdk.nuget.cached(Csproj.packageRequests(node.info), sdk.frameworkDllNames()).dlls

    /**
     * Whether [dll] is newer than the sources it is built from, its project file, and the DLLs of the
     * projects it references.
     */
    private fun isCurrent(sources: List<Path>, nodes: List<CsprojGraph.Node>): Boolean {
        fun stamp(file: Path): Long? = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull()
        val built = stamp(dll) ?: return false
        val inputs = sources + listOfNotNull(Csproj.find(dir)) + nodes.map { it.output }
        return inputs.all { input -> stamp(input)?.let { it <= built } ?: false }
    }

    private fun compilerArguments(
        project: CsprojInfo?,
        sources: List<Path>,
        referenced: List<Path>,
        usings: Path?,
        assemblyInfo: Path?,
    ): List<String> {
        if (project != null) {
            return Csproj.arguments(
                project, sources, sdk.references() + referenced, dll, usings, mainClass = mainClass,
                assemblyInfo = assemblyInfo, resources = CsprojExtras.resources(project, dir),
            )
        }

        return buildList {
            add("-nologo"); add("-noconfig"); add("-target:exe")
            mainClass?.let { add("-main:$it") }
            add("-out:$dll")
            sdk.references().forEach { add("-r:$it") }
            sources.forEach { add(it.toString()) }
        }
    }

    /** Runs a separate `csc` process when the resident compiler cannot be used. Returns its exit code. */
    private fun runPlainCsc(
        launcher: Path,
        arguments: List<String>,
        cancellation: ToolCancellation,
        onLine: (String) -> Unit,
    ): Int {
        val command = listOf(launcher.toString(), sdk.csc.toString()) + arguments
        val referenceCount = command.count { it.startsWith("-r:") }
        val shortened = command.filterNot { it.startsWith("-r:") }.joinToString(" ")

        Log.d(TAG, "csc: $shortened (+$referenceCount references)")

        val process = ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
            .apply { environment().putAll(sdk.environment()) }.start()

        cancellation.attach(process)
        runCatching { process.outputStream.close() }
        process.inputStream.bufferedReader().forEachLine(onLine)

        return process.waitFor()
    }

    private companion object {
        /** Producer label shown in the Problems tab. */
        const val COMPILER_NAME = "csc"

        /**
         * Warnings shown in the Problems tab; all other warnings remain in the editor.
         *
         * Obsolete-use warnings: CS0612 (no message), CS0618 (with a message), CS0672 (overrides an obsolete
         * member), and CS0809 (an obsolete member overrides a non-obsolete one).
         */
        val PROBLEM_WARNINGS = CsDiagnosticTags.DEPRECATED

        /** Matches an unlocated compiler message, such as `error CS5001: message` for a missing `Main`. */
        val UNLOCATED = Regex("""^(error|warning) (CS\d{4}): (.*)$""")
    }
}