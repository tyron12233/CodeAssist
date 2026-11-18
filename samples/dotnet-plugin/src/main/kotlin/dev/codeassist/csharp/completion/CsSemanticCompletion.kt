package dev.codeassist.csharp.completion


import android.util.Log
import dev.codeassist.csharp.project.CsProject
import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.project.csproj.CsprojGraph
import dev.codeassist.csharp.project.csproj.CsprojReferences
import dev.codeassist.csharp.sdk.CsCompleter
import dev.codeassist.csharp.sdk.CsNavigator
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.ToolCancellation
import dev.codeassist.csharp.util.killableTool
import dev.ide.lang.completion.CompletionParams
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Gets completion candidates from Roslyn's semantic model for the editor buffer, including unsaved text,
 * using the project's other source files and settings.
 */
internal class CsSemanticCompletion(private val sdk: DotnetSdk) {

    private val projectReferences = CsprojReferences(sdk)

    /**
     * Returns candidates at the caret, or null when Roslyn cannot answer.
     * The caller then falls back to the symbol table.
     */
    suspend fun itemsFor(params: CompletionParams): List<CsCompletionItems.Item>? {
        val file = runCatching {
            Paths.get(params.document.file.path).toAbsolutePath().normalize()
        }.getOrNull() ?: return null

        val moduleDir = params.module?.let { Paths.get(it.dir.path) }
        val text = params.document.text.toString()

        val lines = ask("complete", file, moduleDir, text) { buffer, arguments, cancellation, onLine ->
            CsCompleter.complete(
                sdk,
                buffer,
                params.offset,
                params.replacementRange.start,
                arguments,
                cancellation,
                onLine,
            )
        } ?: return null

        return CsCompletionItems.parse(lines)
    }

    /**
     * Returns the overloads of the call at [offset], or null when Roslyn cannot answer
     * or the caret is not inside a call. The module is found from [path].
     */
    suspend fun signaturesAt(
        path: String,
        text: String,
        offset: Int,
    ): CsSignatureLines.Result? {
        val file = runCatching {
            Paths.get(path).toAbsolutePath().normalize()
        }.getOrNull() ?: return null

        val lines = ask("signature", file, null, text) { buffer, arguments, cancellation, onLine ->
            CsCompleter.signatures(
                sdk,
                buffer,
                offset,
                arguments,
                cancellation,
                onLine,
            )
        } ?: return null

        return CsSignatureLines.parse(lines)
    }

    /**
     * Returns where the symbol at [offset] is declared in the project's sources: empty when it has no source
     * (a framework or package type), null when Roslyn cannot answer.
     */
    suspend fun definitionsAt(path: String, text: String, offset: Int): List<CsNavigationLines.Definition>? {
        val file = runCatching { Paths.get(path).toAbsolutePath().normalize() }.getOrNull() ?: return null
        var scratch: String? = null

        val lines = ask("definition", file, null, text) { buffer, arguments, cancellation, onLine ->
            scratch = buffer
            CsNavigator.definition(sdk, buffer, offset, arguments, cancellation, onLine)
        } ?: return null

        // The server saw the buffer as a scratch copy; a declaration in it is one in the file being edited.
        return CsNavigationLines.definitions(lines).map {
            if (it.path == scratch) it.copy(path = file.toString()) else it
        }
    }

    /** Returns what the symbol at [offset] is, or null when there is none or Roslyn cannot answer. */
    suspend fun hoverAt(path: String, text: String, offset: Int): CsNavigationLines.Hover? {
        val file = runCatching { Paths.get(path).toAbsolutePath().normalize() }.getOrNull() ?: return null

        val lines = ask("hover", file, null, text) { buffer, arguments, cancellation, onLine ->
            CsNavigator.hover(sdk, buffer, offset, arguments, cancellation, onLine)
        } ?: return null

        return CsNavigationLines.hover(lines)
    }

    /** Returns the names of [text] with what each one is, or null when Roslyn cannot answer. */
    suspend fun tokensFor(path: String, text: String): List<CsNavigationLines.Token>? {
        val file = runCatching { Paths.get(path).toAbsolutePath().normalize() }.getOrNull() ?: return null

        val lines = ask("tokens", file, null, text) { buffer, arguments, cancellation, onLine ->
            CsNavigator.tokens(sdk, buffer, arguments, cancellation, onLine)
        } ?: return null

        return CsNavigationLines.tokens(lines)
    }

    /**
     * Writes [text] as the buffer of [file] beside the project's other sources,
     * then runs [run] with the compiler arguments for that source set.
     */
    private suspend fun ask(
        what: String,
        file: Path,
        module: Path?,
        text: String,
        run: (String, List<String>, ToolCancellation, (String) -> Unit) -> Int?,
    ): List<String>? {
        if (sdk.prepare() !is DotnetSdk.Status.Ready) return null

        // The nearest project file above the buffer decides, in a module that holds several projects.
        val moduleDir = CsprojGraph.owningDir(file) ?: module ?: moduleDirOf(file) ?: return null
        val work = sdk.home.resolve("complete-work")
            .resolve(Integer.toHexString(file.toString().hashCode()))
        val buffer = work.resolve(file.fileName.toString())

        val project = Csproj.load(moduleDir)
        val sources = CsprojGraph.sources(moduleDir, project)
        val others = sources.filter {
            runCatching { it.toAbsolutePath().normalize() }.getOrNull() != file
        }

        val usingsText = Csproj.globalUsingsText(project)
        val usings = work.resolve("GlobalUsings.g.cs").takeIf { usingsText.isNotEmpty() }

        val referenced = project
            ?.let {
                Csproj.resolveReferences(
                    it,
                    moduleDir,
                    sdk.nuget,
                    sdk.frameworkDllNames(),
                ).first
            }
            ?: emptyList()

        // Referenced projects are compiled by the server from their sources, alongside this one.
        val projects = projectReferences.forEditor(Csproj.find(moduleDir), project)

        val arguments = if (project != null) {
            Csproj.arguments(
                project,
                listOf(buffer) + others,
                sdk.references() + referenced + projects.libraries,
                work.resolve("complete.dll"),
                usings,
                forceLibrary = true,
            ) + projects.arguments
        } else {
            buildList {
                add("-nologo")
                add("-noconfig")
                add("-target:library")
                add("-out:" + work.resolve("complete.dll"))
                sdk.references().forEach { add("-r:$it") }
                add(buffer.toString())
                others.forEach { add(it.toString()) }
            }
        }

        val lines = ArrayList<String>()
        val startedAt = System.nanoTime()

        val exit = killableTool { cancellation ->
            Files.createDirectories(work)
            Files.write(buffer, text.toByteArray(Charsets.UTF_8))
            usings?.let {
                Files.write(it, usingsText.toByteArray(Charsets.UTF_8))
            }
            run(buffer.toString(), arguments, cancellation) { lines += it }
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        Log.d(TAG, "$what: exit=$exit, ${lines.size} line(s) in $elapsedMs ms")

        return if (exit == null) null else lines
    }

    /** Finds the folder containing the `.csproj` by walking up from the file. */
    private fun moduleDirOf(file: Path): Path? {
        var dir: Path? = file.parent
        var depth = 0

        while (dir != null && depth < 8) {
            if (Csproj.find(dir) != null) return dir
            dir = dir.parent
            depth++
        }

        return null
    }
}
