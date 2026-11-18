package dev.codeassist.csharp.project.csproj

import dev.codeassist.csharp.project.CsProject
import java.nio.file.Path

/**
 * Assembly information configured by project properties such as `<Version>` and `<Company>`.
 *
 * The SDK converts these properties into `[assembly: ...]` attributes in a generated file.
 * [CsprojExtras.assemblyInfoText] generates the equivalent content.
 *
 * [generate] corresponds to `<GenerateAssemblyInfo>`: when false, the project provides its own attributes.
 */
internal data class CsprojAssemblyInfo(
    val generate: Boolean = true,
    val version: String? = null,
    val assemblyVersion: String? = null,
    val fileVersion: String? = null,
    val informationalVersion: String? = null,
    val company: String? = null,
    val product: String? = null,
    val description: String? = null,
    val copyright: String? = null,
    val title: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(
            version,
            assemblyVersion,
            fileVersion,
            informationalVersion,
            company,
            product,
            description,
            copyright,
            title,
        ).all { it.isNullOrBlank() }
}

/**
 * Handles build settings beyond compilation, including debug symbols, platform targets,
 * embedded resources, and assembly attributes.
 */
internal object CsprojExtras {

    private val DEBUG_TYPES = setOf("full", "portable", "embedded", "pdbonly")

    private val PLATFORMS = mapOf(
        "anycpu" to "anycpu",
        "anycpu32bitpreferred" to "anycpu32bitpreferred",
        "x86" to "x86",
        "x64" to "x64",
        "arm" to "arm",
        "arm64" to "arm64",
    )

    // Compiler flags

    /** Returns the debug flag, or null for `none`, an unknown value, or no setting. */
    fun debugFlag(info: CsprojInfo): String? =
        info.debugType?.takeIf { it in DEBUG_TYPES }?.let { "-debug:$it" }

    /** Returns the platform flag, or null for an unknown value or no setting. */
    fun platformFlag(info: CsprojInfo): String? =
        info.platformTarget?.lowercase()?.let { PLATFORMS[it] }?.let { "-platform:$it" }

    // Documentation

    /** Returns the XML documentation filename beside [dll], such as `App.xml` for `App.dll`. */
    fun documentationName(dll: Path): String =
        dll.fileName.toString().removeSuffix(".dll") + ".xml"

    // Embedded resources

    /**
     * Returns the project files to embed as path and resource-name pairs.
     *
     * Resource names use the root namespace followed by the project-relative path, with
     * `/` replaced by `.` and spaces replaced by `_`. Files under `bin` and `obj` are skipped.
     */
    fun resources(info: CsprojInfo, dir: Path): List<Pair<Path, String>> {
        if (info.embeddedResources.isEmpty()) return emptyList()
        val patterns = info.embeddedResources.map(::globToRegex)
        val namespace = (info.rootNamespace ?: info.assemblyName ?: info.projectName).orEmpty()
        val files = try {
            CsProject.files(dir, Int.MAX_VALUE, skipHidden = false).sorted()
        } catch (e: Exception) {
            emptyList()
        }
        return files.mapNotNull { path ->
            val relative = dir.relativize(path).toString().replace('\\', '/')
            if (patterns.none { it.matches(relative) }) return@mapNotNull null
            path to resourceName(namespace, relative)
        }
    }

    fun resourceName(namespace: String, relative: String): String {
        val tail = relative.replace(' ', '_').replace('/', '.')
        return if (namespace.isEmpty()) tail else "$namespace.$tail"
    }

    // Assembly information

    /**
     * Generates the C# file containing the project's assembly attributes.
     *
     * Returns an empty string when no attributes are configured or generation is disabled.
     * `<Version>` supplies the default assembly and file versions as numeric versions
     * (`1.2.3-beta` becomes `1.2.3.0`) and the informational version as written.
     * Each version can also be configured independently.
     */
    fun assemblyInfoText(info: CsprojInfo): String {
        val a = info.assemblyInfo
        if (!a.generate || a.isEmpty) return ""
        val numeric = a.version?.let(::numericVersion)
        val lines = buildList {
            (a.assemblyVersion ?: numeric)?.let { add("AssemblyVersion" to it) }
            (a.fileVersion ?: numeric)?.let { add("AssemblyFileVersion" to it) }
            (a.informationalVersion ?: a.version)?.let { add("AssemblyInformationalVersion" to it) }
            a.company?.let { add("AssemblyCompany" to it) }
            a.product?.let { add("AssemblyProduct" to it) }
            a.description?.let { add("AssemblyDescription" to it) }
            a.copyright?.let { add("AssemblyCopyright" to it) }
            a.title?.let { add("AssemblyTitle" to it) }
        }.filter { it.second.isNotBlank() }
        if (lines.isEmpty()) return ""
        return buildString {
            append("// <auto-generated/> from the project file by the C# plugin.\n")
            for ((attribute, value) in lines) {
                append("[assembly: System.Reflection.").append(attribute).append("Attribute(\"")
                append(escape(value)).append("\")]\n")
            }
        }
    }

    /** Converts a version to four numeric components, or returns null if it is not numeric. */
    fun numericVersion(version: String): String? {
        val core = version.trim().substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 4 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        return (parts + List(4 - parts.size) { "0" }).joinToString(".")
    }

    // Private helpers

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\r", "").replace("\n", " ")
}