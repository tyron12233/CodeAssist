package dev.codeassist.csharp.project.csproj

import android.util.Log
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.codeassist.csharp.util.TAG
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a project gets from the projects it references, for the editor's requests to the server.
 *
 * The server takes a referenced project as `-projectref:<response file>`, a file holding that project's own
 * compiler arguments, and compiles it in memory from its sources on disk. So the editor sees a referenced
 * project as it is now, whether or not it has been built. This writes those files.
 */
internal class CsprojReferences(private val sdk: DotnetSdk) {

    /**
     * [arguments] to add to the referencing project's own, [libraries] it also compiles against (the DLLs
     * and packages the referenced projects use reach it too), and what could not be followed.
     */
    class Editor(val arguments: List<String>, val libraries: List<Path>, val problems: List<String>) {
        companion object {
            val NONE = Editor(emptyList(), emptyList(), emptyList())
        }
    }

    fun forEditor(projectFile: Path?, info: CsprojInfo?): Editor {
        if (projectFile == null || info == null) return Editor.NONE
        val key = "-projectkey:$projectFile"
        if (info.projectReferences.isEmpty()) return Editor(listOf(key), emptyList(), emptyList())

        val graph = CsprojGraph.referenced(projectFile, info)
        val frameworkDlls = sdk.frameworkDllNames()
        val responseFiles = LinkedHashMap<Path, Path>()
        val libraries = LinkedHashMap<String, Path>()

        for (node in graph.nodes) {
            val own = Csproj.resolveReferences(node.info, node.dir, sdk.nuget, frameworkDlls).first
            own.forEach { libraries.putIfAbsent(it.fileName.toString().lowercase(), it) }
            // Dependencies come first, so every project this one needs already has its file.
            val needed = CsprojGraph.referenced(node.file, node.info).nodes.mapNotNull { responseFiles[it.file] }
            responseFiles[node.file] = write(node, own, needed)
        }

        return Editor(
            listOf(key) + responseFiles.values.map { "-projectref:$it" },
            libraries.values.toList(),
            graph.problems,
        )
    }

    /** Writes [node]'s arguments where the server reads them, and returns the file. */
    private fun write(node: CsprojGraph.Node, own: List<Path>, needed: List<Path>): Path {
        val work = sdk.home.resolve("projects").resolve(Integer.toHexString(node.file.toString().hashCode()))
        Files.createDirectories(work)

        val usingsText = Csproj.globalUsingsText(node.info)
        val usings = work.resolve("GlobalUsings.g.cs").takeIf { usingsText.isNotEmpty() }
        usings?.let { writeIfChanged(it, usingsText) }

        val arguments = Csproj.arguments(
            node.info,
            CsprojGraph.sources(node.dir, node.info),
            sdk.references() + own,
            work.resolve("${node.assemblyName}.dll"),
            usings,
            forceLibrary = true,
        ) + needed.map { "-projectref:$it" }

        return work.resolve("project.rsp").also { writeIfChanged(it, arguments.joinToString("\n")) }
    }

    private fun writeIfChanged(file: Path, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val same = runCatching { Files.readAllBytes(file).contentEquals(bytes) }.getOrDefault(false)
        if (!same) {
            runCatching { Files.write(file, bytes) }
                .onFailure { Log.d(TAG, "references: could not write $file: $it") }
        }
    }

    companion object {
        /** Whether [argument] is one only the server understands, which `csc` itself would reject. */
        fun isServerOnly(argument: String): Boolean =
            argument.startsWith("-projectref:") || argument.startsWith("-projectkey:")
    }
}
