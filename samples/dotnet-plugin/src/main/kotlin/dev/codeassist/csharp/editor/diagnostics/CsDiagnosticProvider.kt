package dev.codeassist.csharp.editor.diagnostics


import android.util.Log
import dev.codeassist.csharp.CSharpPlugin
import dev.codeassist.csharp.completion.CsSymbols
import dev.codeassist.csharp.editor.CsFileNames
import dev.codeassist.csharp.editor.CsModules
import dev.codeassist.csharp.editor.CsUsingCleanup
import dev.codeassist.csharp.editor.CsWorkspace
import dev.codeassist.csharp.editor.quickfixes.CsUsingFix
import dev.codeassist.csharp.project.CsProject
import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.project.csproj.CsprojGraph
import dev.codeassist.csharp.project.csproj.CsprojReferences
import dev.codeassist.csharp.project.csproj.CsprojInfo
import dev.codeassist.csharp.project.nuget.CsPackageRestorer
import dev.codeassist.csharp.sdk.CsChecker
import dev.codeassist.csharp.sdk.CsCompilerHost
import dev.codeassist.csharp.sdk.CsCompleter
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.codeassist.csharp.util.RestoreNotice
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.killableTool
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.DiagnosticTag
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.platform.log.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports C# compiler diagnostics for the current editor buffer.
 *
 * Uses the same Roslyn compiler as Run. The current buffer is compiled from its unsaved text,
 * while the module's other source files are read from disk. Compilation uses library output
 * so a missing Main does not produce CS5001.
 *
 * Only diagnostics belonging to the current buffer are reported.
 */
internal class CsDiagnosticProvider(
    private val sdk: DotnetSdk,
    private val log: Logger,
    private val symbols: CsSymbols,
    private val restorer: CsPackageRestorer,
    private val unused: CsUsingCleanup.Unused,
) : DiagnosticProvider {

    private val projectReferences = CsprojReferences(sdk)

    override val id = "csharp.roslyn"
    override val languages = setOf(LanguageId(CSharpPlugin.LANGUAGE))

    /** One cached result, keyed by the buffer path and its current project state. */
    private var cachedKey: Pair<String, Int>? = null
    private var cached: List<Diagnostic> = emptyList()

    /** Starts the completer once, after the first diagnostic check. */
    private val completerWarmed = AtomicBoolean(false)

    override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> {
        Log.d(TAG, "diagnose: called for ${target.file.path}")

        if (sdk.prepare() !is DotnetSdk.Status.Ready) {
            Log.d(TAG, "diagnose: .NET not ready, skipping")
            return emptyList()
        }

        val file = runCatching {
            Paths.get(target.file.path).toAbsolutePath().normalize()
        }.getOrNull() ?: return emptyList()

        val text = target.parsed.text().toString()

        CsModules.remember(target.module)

        // The project the file belongs to: in a module holding several, the nearest project file above it.
        val moduleDir = CsprojGraph.owningDir(file) ?: Paths.get(target.module.dir.path)
        val key = file.toString() to
            (text.hashCode() * 31 + packagesStamp(moduleDir)) * 31 +
            restorer.failuresFor(moduleDir).hashCode()

        synchronized(this) {
            if (cachedKey == key) {
                Log.d(TAG, "diagnose: cached")
                return cached
            }
        }

        // Keep each buffer's compiler copy separate, even when files share a name.
        val work = sdk.home.resolve("editor")
            .resolve(Integer.toHexString(file.toString().hashCode()))
        val buffer = work.resolve(file.fileName.toString())
        val bufferPath = buffer.toAbsolutePath().normalize()

        // Use the same project settings as Run.
        val project = Csproj.load(moduleDir)
        val sources = CsprojGraph.sources(moduleDir, project)

        val others = sources.filter {
            runCatching { it.toAbsolutePath().normalize() }.getOrNull() != file
        }

        val usingsText = Csproj.globalUsingsText(project)
        val usings = work.resolve("GlobalUsings.g.cs")
            .takeIf { usingsText.isNotEmpty() }

        if (project != null && project.packageReferences.isNotEmpty()) {
            if (CsWorkspace.available && restorer.pending(project, moduleDir)) {
                // The restore requests another check when it finishes. Until then, keep the last result.
                restorer.restoreInBackground(project, moduleDir)
                Log.d(TAG, "diagnose: packages are being restored, ${file.fileName} is checked again after")
                return synchronized(this) {
                    if (cachedKey?.first == file.toString()) cached else emptyList()
                }
            }

            restorer.restore(project, moduleDir)
        }

        val referenced = project
            ?.let {
                Csproj.resolveReferences(it, moduleDir, sdk.nuget, sdk.frameworkDllNames()).first
            }
            ?: emptyList()

        // Projects this one references are compiled by the server from their sources; `csc` knows nothing
        // of that, so the fallbacks below go without them.
        val projects = projectReferences.forEditor(Csproj.find(moduleDir), project)
        val arguments = checkArguments(project, buffer, others, work, usings, referenced + projects.libraries)

        val lines = ArrayList<String>()
        val startedAt = System.nanoTime()
        var viaChecker = false

        val exitCode = killableTool { cancellation ->
            Files.createDirectories(work)
            Files.write(buffer, text.toByteArray(Charsets.UTF_8))
            usings?.let {
                Files.write(it, usingsText.toByteArray(Charsets.UTF_8))
            }

            // Prefer the checker, which reports syntax and semantic errors together.
            // Fall back to the compiler host when the checker cannot be used.
            val checked = CsChecker.checkFile(
                sdk, buffer.toString(), arguments + projects.arguments, cancellation,
            ) { lines += it }

            if (checked != null) {
                viaChecker = true
                checked
            } else if (cancellation.isCancelled) {
                null
            } else {
                lines.clear()
                CsCompilerHost.compile(sdk, arguments, cancellation) { lines += it }
            }
        }

        target.checkCanceled()
        warmCompleter()

        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        Log.d(TAG, "diagnose: host exit=$exitCode, ${lines.size} output lines in $tookMs ms")

        if (exitCode == null) {
            log.warn("the resident compiler could not check ${file.fileName}")
            return emptyList()
        }

        var diagnostics = diagnosticsFor(bufferPath, lines, text)

        // The compiler may hide semantic errors while another source file has syntax errors.
        if (!viaChecker && exitCode != 0 && diagnostics.isEmpty()) {
            diagnostics = checkWithoutBrokenFiles(target, bufferPath, lines, text, others) { remaining ->
                checkArguments(project, buffer, remaining, work, usings, referenced)
            } ?: diagnostics
        }

        diagnostics = diagnostics +
            unusedUsings(bufferPath, lines, text, file, diagnostics) +
            fileNameHint(text, file)

        diagnostics = diagnostics + restoreWarning(moduleDir, text)

        Log.d(TAG, "diagnose: ${diagnostics.size} diagnostics for ${file.fileName}")

        // Build the symbol table when a missing-using quick fix needs it.
        if (diagnostics.any { it.code in CsUsingFix.CODES } &&
            symbols.cachedTable(moduleDir) == null
        ) {
            runCatching {
                symbols.tableFor(project, moduleDir, sources)
            }
            target.checkCanceled()
        }

        if (exitCode != 0 && diagnostics.isEmpty()) {
            Log.d(TAG, "diagnose: no located error for exit $exitCode; first line: ${lines.firstOrNull()}")
        }

        synchronized(this) {
            cachedKey = key
            cached = diagnostics
        }

        return diagnostics
    }

    /**
     * Starts completion in the background after the first check, avoiding a build competing
     * with the initial diagnostic check.
     */
    private fun warmCompleter() {
        if (!completerWarmed.compareAndSet(false, true)) return

        Thread(
            { runCatching { CsCompleter.warm(sdk) } },
            "cs-complete-warm",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Hints when the single public type does not match the file name. */
    private fun fileNameHint(text: String, file: Path): List<Diagnostic> {
        val mismatch = CsFileNames.mismatch(text, file.fileName.toString())
            ?: return emptyList()

        return listOf(
            Diagnostic(
                range = TextRange(mismatch.start, mismatch.end),
                severity = Severity.INFO,
                message = "Type '${mismatch.name}' does not match the file name '${file.fileName}'",
                source = DiagnosticSource.Analyzer(AnalyzerId("csharp.file-name")),
                code = "CSFILENAME",
            ),
        )
    }

    /**
     * Converts hidden CS8019 diagnostics into unused-using hints.
     *
     * Hints are omitted while the buffer has errors because unresolved names can make a
     * required using directive appear unused.
     */
    private fun unusedUsings(
        bufferPath: Path,
        lines: List<String>,
        text: String,
        file: Path,
        found: List<Diagnostic>,
    ): List<Diagnostic> {
        unused.put(file.toString(), emptySet())
        if (found.any { it.severity == Severity.ERROR }) return emptyList()

        val offsets = CsLineOffsets(text)
        val texts = HashSet<String>()

        val out = lines
            .mapNotNull { CsDiagnostics.parseUnusedUsing(it) }
            .filter {
                runCatching { Paths.get(it.path).toAbsolutePath().normalize() }.getOrNull() == bufferPath
            }
            .mapNotNull { hidden ->
                val start = offsets.offsetOf(hidden.line, hidden.column)
                val lineText = CsUsingCleanup.directiveAt(text, start)
                    ?: return@mapNotNull null

                texts += lineText

                val end = text.indexOf('\n', start).let {
                    if (it < 0) text.length else it
                }

                Diagnostic(
                    range = TextRange(
                        start,
                        text.substring(start, end).trimEnd().length + start,
                    ),
                    severity = Severity.HINT,
                    message = hidden.message,
                    source = DiagnosticSource.Compiler,
                    code = hidden.code,
                    tags = setOf(DiagnosticTag.UNUSED),
                )
            }

        unused.put(file.toString(), texts)
        return out
    }

    /** Shows the last package restore failure on the first line of the buffer. */
    private fun restoreWarning(moduleDir: Path, text: String): List<Diagnostic> {
        val problems = restorer.failuresFor(moduleDir)
        if (problems.isEmpty() || text.isEmpty()) return emptyList()

        val lineEnd = text.indexOf('\n')
            .let { if (it < 0) text.length else it }
            .coerceAtLeast(1)

        return listOf(
            Diagnostic(
                range = TextRange(0, lineEnd.coerceAtMost(text.length)),
                severity = Severity.WARNING,
                message = RestoreNotice.fileWarning(problems),
                source = DiagnosticSource.Compiler,
                code = "NUGET_RESTORE",
            ),
        )
    }

    /**
     * Includes project settings and the number of unpacked package DLLs in the cache key.
     * A saved project change can affect every source file without changing its buffer text.
     */
    private fun packagesStamp(moduleDir: Path): Int {
        val project = Csproj.load(moduleDir) ?: return 0

        val unpacked = if (project.packageReferences.isEmpty()) {
            0
        } else {
            sdk.nuget.cached(
                Csproj.packageRequests(project),
                sdk.frameworkDllNames(),
            ).dlls.size
        }

        return project.hashCode() * 31 + unpacked
    }

    /** Builds compiler arguments for the buffer and the other module sources, using library output. */
    private fun checkArguments(
        project: CsprojInfo?,
        buffer: Path,
        others: List<Path>,
        work: Path,
        usings: Path?,
        referenced: List<Path>,
    ): List<String> {
        val output = work.resolve("check.dll")

        if (project != null) {
            return Csproj.arguments(
                project,
                listOf(buffer) + others,
                sdk.references() + referenced,
                output,
                usings,
                forceLibrary = true,
            )
        }

        return buildList {
            add("-nologo")
            add("-noconfig")
            add("-target:library")
            add("-out:$output")
            sdk.references().forEach { add("-r:$it") }
            add(buffer.toString())
            others.forEach { add(it.toString()) }
        }
    }

    /** Converts compiler output belonging to the current buffer into editor diagnostics. */
    private fun diagnosticsFor(
        bufferPath: Path,
        lines: List<String>,
        text: String,
    ): List<Diagnostic> {
        val offsets = CsLineOffsets(text)

        return lines
            .mapNotNull { CsDiagnostics.parse(it) }
            // Match the compiler's buffer copy, not the original file path.
            .filter {
                runCatching { Paths.get(it.path).toAbsolutePath().normalize() }.getOrNull() == bufferPath
            }
            // Top-level statements are valid programs, even though this check compiles as a library.
            .filter { it.code != "CS8805" }
            .map { diagnostic ->
                Diagnostic(
                    range = offsets.tokenAt(diagnostic.line, diagnostic.column),
                    severity = when (diagnostic.severity) {
                        CsDiagnostic.Severity.ERROR -> Severity.ERROR
                        CsDiagnostic.Severity.WARNING -> Severity.WARNING
                    },
                    message = diagnostic.message,
                    source = DiagnosticSource.Compiler,
                    code = diagnostic.code,
                    tags = CsDiagnosticTags.of(diagnostic.code),
                )
            }
    }

    /**
     * Rechecks the buffer without other files that produced syntax errors.
     *
     * Types declared in excluded files are missing from this check, so diagnostics referring
     * to those types are filtered out.
     */
    private suspend fun checkWithoutBrokenFiles(
        target: AnalysisTarget,
        bufferPath: Path,
        firstLines: List<String>,
        text: String,
        others: List<Path>,
        argumentsFor: (List<Path>) -> List<String>,
    ): List<Diagnostic>? {
        val broken = firstLines
            .mapNotNull { CsDiagnostics.parse(it) }
            .filter { it.severity == CsDiagnostic.Severity.ERROR }
            .mapNotNull {
                runCatching { Paths.get(it.path).toAbsolutePath().normalize() }.getOrNull()
            }
            .filter { it != bufferPath }
            .toSet()

        if (broken.isEmpty()) return null

        val remaining = others.filter {
            runCatching { it.toAbsolutePath().normalize() }.getOrNull() !in broken
        }

        val lines = ArrayList<String>()
        val exitCode = killableTool { cancellation ->
            CsCompilerHost.compile(sdk, argumentsFor(remaining), cancellation) { lines += it }
        }

        target.checkCanceled()
        if (exitCode == null) return null

        val leftOutTypes = broken.flatMap { declaredTypeNames(readText(it)) }.toSet()

        val kept = lines.filterNot { line ->
            CsDiagnostics.parse(line)?.let {
                mentionsType(it.message, leftOutTypes)
            } == true
        }

        Log.d(TAG, "diagnose: rechecked without ${broken.size} broken file(s), ${lines.size - kept.size} dropped")
        return diagnosticsFor(bufferPath, kept, text)
    }

    private fun readText(path: Path): String =
        runCatching {
            String(Files.readAllBytes(path), Charsets.UTF_8)
        }.getOrDefault("")
}
