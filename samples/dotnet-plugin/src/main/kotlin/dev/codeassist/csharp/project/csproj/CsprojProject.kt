package dev.codeassist.csharp.project.csproj

import android.util.Log
import dev.codeassist.csharp.project.CsProject
import dev.codeassist.csharp.project.nuget.NuGet
import dev.codeassist.csharp.util.TAG
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A `<PackageReference>` containing the package [id] and its [version] as written.
 *
 * The version may be a range or a floating version, or null when none is specified.
 */
internal data class CsprojPackage(val id: String, val version: String?)

/**
 * The SDK-style `.csproj` settings that affect how this plugin compiles a project.
 *
 * The file is read as plain XML, not evaluated by MSBuild. Only properties and items
 * that this compiler can honor are retained; unsupported settings are not inferred.
 *
 * Conditional properties (`Condition="..."`) are currently read as if unconditional.
 * When a property is set more than once, its last value wins, as in MSBuild.
 */
internal data class CsprojInfo(
    val assemblyName: String?,
    val outputType: String,
    val langVersion: String?,
    val nullable: Boolean,
    val implicitUsings: Boolean,
    val defines: List<String>,
    val includes: List<String>,
    val removes: List<String>,
    val projectReferences: List<String>,
    val packageReferences: List<CsprojPackage>,
    val targetFramework: String?,
    val references: List<CsprojReference>,
    val outDir: String? = null,
    val appendTargetFramework: Boolean = true,
    val projectName: String? = null,
    /** `<StartupObject>`: the class whose `Main` is the entry point; Module Settings > Run takes precedence. */
    val startupObject: String? = null,
    /** `<Nullable>` as written; [nullable] is true only when the value is `enable`. */
    val nullableMode: String? = null,
    val allowUnsafe: Boolean = false,
    val treatWarningsAsErrors: Boolean = false,
    val warningsAsErrors: List<String> = emptyList(),
    val warningsNotAsErrors: List<String> = emptyList(),
    val noWarn: List<String> = emptyList(),
    val warningLevel: Int? = null,
    val checkedArithmetic: Boolean = false,
    val optimize: Boolean = false,
    /** `<Using Include="...">` items: namespaces or, with [CsprojUsing.static], types imported in every file. */
    val usings: List<CsprojUsing> = emptyList(),
    /** `<Using Remove="...">`: implicit usings removed by the project. */
    val removedUsings: List<String> = emptyList(),
    /** `<EnableDefaultCompileItems>`: false means only explicit `Compile Include` files are compiled. */
    val enableDefaultCompileItems: Boolean = true,
    /** `<DebugType>` as written (`full`, `portable`, `embedded`, `pdbonly`, `none`); null uses the compiler default. */
    val debugType: String? = null,
    /** `<PlatformTarget>` as written (`AnyCPU`, `x64`, and so on); null uses the compiler default. */
    val platformTarget: String? = null,
    /** `<GenerateDocumentationFile>`: whether to write the XML documentation file beside the DLL. */
    val generateDocumentation: Boolean = false,
    /** `<EmbeddedResource Include="...">` patterns, relative to the project folder, using `/` separators. */
    val embeddedResources: List<String> = emptyList(),
    val rootNamespace: String? = null,
    val assemblyInfo: CsprojAssemblyInfo = CsprojAssemblyInfo(),
) {
    /** A library has no entry point: it is built but never started. */
    val isLibrary: Boolean
        get() = outputType.equals("Library", ignoreCase = true)
}

/** A `<Using Include="N" Alias="A" Static="true" />` item. */
internal data class CsprojUsing(
    val name: String,
    val alias: String? = null,
    val static: Boolean = false,
)

/**
 * A `<Reference Include="...">` with its name, optional `HintPath`, and copy-local setting.
 *
 * `<Private>false</Private>` disables copying the DLL beside the output.
 */
internal data class CsprojReference(
    val name: String,
    val hintPath: String?,
    val copyLocal: Boolean = true,
)

/** Reads a project file and converts its supported settings into compiler arguments. */
internal object Csproj {

    /** Maximum depth below the module folder when searching for a `.csproj`. */
    private const val MAX_PROJECT_DEPTH = 3

    private val cache = java.util.concurrent.ConcurrentHashMap<Path, Cached>()

    private class Cached(
        val stamp: Pair<Long, Long>,
        val info: CsprojInfo,
    )

    private val IMPLICIT_USINGS = listOf(
        "System",
        "System.Collections.Generic",
        "System.IO",
        "System.Linq",
        "System.Net.Http",
        "System.Threading",
        "System.Threading.Tasks",
    )

    private val NULLABLE_MODES = setOf("enable", "disable", "warnings", "annotations")

    /**
     * Finds the `.csproj` in [dir].
     *
     * Checks [dir] itself first, then searches up to three folders below it,
     * excluding `bin` and `obj`. Returns null when no project file exists.
     */
    fun find(dir: Path): Path? {
        if (!Files.isDirectory(dir)) return null
        val direct = Files.list(dir).use { entries ->
            entries.filter { it.fileName.toString().endsWith(".csproj") }.sorted().findFirst().orElse(null)
        }
        if (direct != null) return direct
        return CsProject.files(dir, MAX_PROJECT_DEPTH, skipHidden = false)
            .filter { it.fileName.toString().endsWith(".csproj") }.minOrNull()
    }

    /**
     * Loads the project in [dir], or returns null when none exists or it cannot be read.
     *
     * Parsed data is cached while the file's modification time and size remain unchanged.
     * Only actual reads are logged.
     */
    fun load(dir: Path): CsprojInfo? {
        val file = find(dir) ?: return null
        val stamp = try {
            Files.getLastModifiedTime(file).toMillis() to Files.size(file)
        } catch (e: Exception) {
            null
        }
        if (stamp != null) {
            cache[file]?.let { if (it.stamp == stamp) return it.info }
        }
        return try {
            read(file).also { info ->
                logSummary(file, info)
                if (info.targetFramework != null && info.targetFramework != "net10.0") {
                    Log.d(TAG, "csproj: targets ${info.targetFramework}; this compiler has net10.0 references only")
                }
                if (stamp != null) cache[file] = Cached(stamp, info)
            }
        } catch (e: Exception) {
            Log.d(TAG, "csproj: could not read ${file.fileName}: $e")
            cache.remove(file)
            null
        }
    }

    /** Parses [file]. Throws when it is not well-formed XML. */
    fun read(file: Path): CsprojInfo {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val root = factory.newDocumentBuilder().parse(file.toFile()).documentElement

        // Conditions are evaluated for a Debug build of the project's own target framework.
        val vars = HashMap<String, String>().apply {
            put("Configuration", "Debug")
            put("Platform", "AnyCPU")
            lastValue(root, "TargetFramework") { true }?.let { put("TargetFramework", it) }
        }

        /** Returns true when [element] and all its ancestors have conditions that hold. */
        fun active(element: Element): Boolean {
            var node: org.w3c.dom.Node? = element
            while (node is Element) {
                if (!CsprojCondition.holds(node.getAttribute("Condition"), vars)) return false
                node = node.parentNode
            }
            return true
        }

        /** Returns the last non-empty value of [name], allowing later settings to override earlier ones. */
        fun property(name: String): String? = lastValue(root, name, ::active)

        fun flag(name: String): Boolean = property(name).equals("true", ignoreCase = true)

        /** Returns the separated values of [name], excluding unresolved `$(Name)` references. */
        fun list(name: String): List<String> = property(name)?.split(';', ',')?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.contains("$(") } ?: emptyList()

        /** Returns the `;`-separated values of [attribute] on active elements named [tag]. */
        fun items(tag: String, attribute: String): List<String> {
            val nodes = root.getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as Element }.filter(::active).flatMap { element ->
                element.getAttribute(attribute)
                    .replace('\\', '/').split(';').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        val usingNodes = root.getElementsByTagName("Using")
        val usings = (0 until usingNodes.length).map { usingNodes.item(it) as Element }.filter(::active)
            .flatMap { element ->
                element.getAttribute("Include").split(';').map { it.trim() }.filter { it.isNotEmpty() }.map {
                    CsprojUsing(
                        it,
                        element.getAttribute("Alias").trim().ifEmpty { null },
                        element.getAttribute("Static").equals("true", ignoreCase = true),
                    )
                }
            }

        return CsprojInfo(
            assemblyName = property("AssemblyName"),
            // The SDK defaults to a library when OutputType is not specified.
            outputType = property("OutputType") ?: "Library",
            langVersion = property("LangVersion"),
            nullable = property("Nullable").equals("enable", ignoreCase = true),
            implicitUsings = property("ImplicitUsings").equals("enable", ignoreCase = true),
            defines = list("DefineConstants"),
            includes = items("Compile", "Include"),
            removes = items("Compile", "Remove"),
            projectReferences = items("ProjectReference", "Include"),
            packageReferences = readPackages(root, ::active),
            targetFramework = property("TargetFramework"),
            references = readReferences(root, ::active),
            outDir = property("OutDir"),
            appendTargetFramework = !property("AppendTargetFrameworkToOutputPath").equals("false", ignoreCase = true),
            projectName = file.fileName.toString().removeSuffix(".csproj"),
            startupObject = property("StartupObject"),
            nullableMode = property("Nullable")?.lowercase(),
            allowUnsafe = flag("AllowUnsafeBlocks"),
            treatWarningsAsErrors = flag("TreatWarningsAsErrors"),
            warningsAsErrors = list("WarningsAsErrors"),
            warningsNotAsErrors = list("WarningsNotAsErrors"),
            noWarn = list("NoWarn"),
            warningLevel = property("WarningLevel")?.toIntOrNull(),
            checkedArithmetic = flag("CheckForOverflowUnderflow"),
            optimize = flag("Optimize"),
            usings = usings.filter { it.name.isNotEmpty() },
            removedUsings = items("Using", "Remove"),
            enableDefaultCompileItems = !property("EnableDefaultCompileItems").equals("false", ignoreCase = true),
            debugType = property("DebugType")?.lowercase(),
            platformTarget = property("PlatformTarget"),
            generateDocumentation = flag("GenerateDocumentationFile"),
            embeddedResources = items("EmbeddedResource", "Include"),
            rootNamespace = property("RootNamespace"),
            assemblyInfo = CsprojAssemblyInfo(
                generate = !property("GenerateAssemblyInfo").equals("false", ignoreCase = true),
                version = property("Version"),
                assemblyVersion = property("AssemblyVersion"),
                fileVersion = property("FileVersion"),
                informationalVersion = property("InformationalVersion"),
                company = property("Company") ?: property("Authors"),
                product = property("Product"),
                description = property("Description"),
                copyright = property("Copyright"),
                title = property("AssemblyTitle"),
            ),
        )
    }

    /** Returns the last non-empty text of elements named [name] under [root] that [accept] allows. */
    private fun lastValue(root: Element, name: String, accept: (Element) -> Boolean): String? {
        val nodes = root.getElementsByTagName(name)
        var found: String? = null
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            if (!accept(element)) continue
            val text = element.textContent?.trim().orEmpty()
            if (text.isNotEmpty()) found = text
        }
        return found
    }

    // Source files and output paths

    /**
     * Returns the `.cs` files the project compiles.
     *
     * Applies the project's Compile items to [candidates], which are all `.cs` files
     * under [dir] outside `bin` and `obj`. With no Include items, every candidate is compiled.
     */
    fun sources(info: CsprojInfo, dir: Path, candidates: List<Path>): List<Path> {
        // When default items are disabled, only explicitly included files are compiled.
        if (!info.enableDefaultCompileItems && info.includes.isEmpty()) return emptyList()
        val includes = info.includes.map(::globToRegex)
        val removes = info.removes.map(::globToRegex)
        return candidates.filter { path ->
            val relative = dir.relativize(path).toString().replace('\\', '/')
            (includes.isEmpty() || includes.any { it.matches(relative) }) && removes.none { it.matches(relative) }
        }
    }

    /**
     * Returns the project's output folder.
     *
     * `OutDir` takes precedence. Otherwise, the default is `bin/Debug/<framework>/`,
     * without the framework folder when `AppendTargetFrameworkToOutputPath` is false.
     * The output remains inside the project folder.
     */
    fun outputDir(dir: Path, info: CsprojInfo?): Path {
        val projectDir = projectFolder(dir)
        info?.outDir?.let { return projectDir.resolve(it.replace('\\', '/')).normalize() }
        val debug = projectDir.resolve("bin").resolve("Debug")
        return if (info?.appendTargetFramework == false) debug else debug.resolve(info?.targetFramework ?: "net10.0")
    }

    /** Returns the assembly name from `AssemblyName`, the project filename, or the module name, in that order. */
    fun assemblyName(info: CsprojInfo?, moduleName: String): String =
        CsProject.assemblyName(info?.assemblyName ?: info?.projectName ?: moduleName)

    /** Returns the folder containing the `.csproj`, or [dir] when there is no project file. */
    private fun projectFolder(dir: Path): Path = find(dir)?.parent ?: dir

    // References and packages

    /**
     * Resolves project references to DLL files and returns the names of references that could not be found.
     *
     * A reference is searched for at its `HintPath`, then as `<name>.dll` in the project folder and `lib/`.
     */
    fun resolveReferences(
        info: CsprojInfo,
        dir: Path,
        nuget: NuGet? = null,
        frameworkDlls: Set<String> = emptySet(),
    ): Pair<List<Path>, List<String>> {
        val projectDir = projectFolder(dir)
        val found = ArrayList<Path>()
        val missing = ArrayList<String>()
        for (reference in info.references) {
            val hit = locate(reference, dir, projectDir)
            if (hit != null) found.add(hit) else missing.add(reference.name)
        }
        // Packages must already be unpacked; restoring them is NuGet's responsibility.
        if (nuget != null && info.packageReferences.isNotEmpty()) {
            found.addAll(nuget.cached(packageRequests(info), frameworkDlls).dlls)
        }
        return found to missing
    }

    /** Returns referenced DLLs that should be copied beside the output. */
    fun copyLocal(info: CsprojInfo, dir: Path): List<Path> {
        val projectDir = projectFolder(dir)
        return info.references.filter { it.copyLocal }.mapNotNull { locate(it, dir, projectDir) }
    }

    /** Returns the project's packages in the format expected by NuGet restore. */
    fun packageRequests(info: CsprojInfo): List<NuGet.Request> =
        info.packageReferences.map { NuGet.Request(it.id, it.version) }

    /** Returns the folder of a `.csproj` at [path], or null when [path] is not a project-file path. */
    fun dirOfProjectFile(path: String): java.nio.file.Path? {
        if (!path.endsWith(".csproj", ignoreCase = true)) return null
        return runCatching { java.nio.file.Paths.get(path).toAbsolutePath().normalize().parent }.getOrNull()
    }

    private fun readPackages(root: Element, active: (Element) -> Boolean): List<CsprojPackage> {
        val nodes = root.getElementsByTagName("PackageReference")
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter(active).map { element ->
            val version = attributeOrChild(element, "Version").ifEmpty { attributeOrChild(element, "VersionOverride") }
            CsprojPackage(element.getAttribute("Include").trim(), version.ifEmpty { null })
        }.filter { it.id.isNotEmpty() }
    }

    private fun readReferences(root: Element, active: (Element) -> Boolean): List<CsprojReference> {
        val nodes = root.getElementsByTagName("Reference")
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter(active).map { element ->
            val name = element.getAttribute("Include")
                .replace('\\', '/').substringBefore(',').trim().removeSuffix(".dll")
            val hint = attributeOrChild(element, "HintPath").replace('\\', '/').takeIf { it.isNotEmpty() }
            val copyLocal = !attributeOrChild(element, "Private").equals("false", ignoreCase = true)
            CsprojReference(name, hint, copyLocal)
        }.filter { it.name.isNotEmpty() }
    }

    /** Reads an attribute value or, when absent, the text of a child element with the same name. */
    private fun attributeOrChild(element: Element, name: String): String =
        element.getAttribute(name).ifEmpty {
            (element.getElementsByTagName(name).item(0) as? Element)?.textContent.orEmpty()
        }.trim()

    /** The DLL a [reference] points to, or null when none of its candidate paths exists. */
    private fun locate(reference: CsprojReference, dir: Path, projectDir: Path): Path? {
        val candidates = buildList {
            reference.hintPath?.let { add(projectDir.resolve(it)) }
            reference.hintPath?.let { add(dir.resolve(it)) }
            add(dir.resolve(reference.name + ".dll"))
            add(dir.resolve("lib").resolve(reference.name + ".dll"))
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }?.normalize()
    }

    // Compiler settings

    /** Returns the global usings source file, or an empty string when there are no usings. */
    fun globalUsingsText(info: CsprojInfo?): String {
        if (info == null) return ""
        val removed = info.removedUsings.toSet()
        val implicit = if (info.implicitUsings) IMPLICIT_USINGS.filter { it !in removed } else emptyList()
        return buildString {
            for (name in implicit) append("global using global::$name;\n")
            for (using in info.usings) {
                append("global using ")
                if (using.static) append("static ")
                if (using.alias != null) append(using.alias).append(" = ")
                append("global::").append(using.name).append(";\n")
            }
        }
    }

    /** Returns the reason this compiler cannot build the project, or null when it can. */
    fun unsupported(info: CsprojInfo): String? = when {
        info.projectReferences.isNotEmpty() ->
            "project references are not supported yet: ${info.projectReferences.joinToString()}"
        else -> null
    }

    /** Removes the `CS` prefix from a warning code, leaving other values unchanged. */
    private fun number(code: String): String =
        code.trim().let { if (it.startsWith("CS", ignoreCase = true)) it.drop(2) else it }

    /**
     * Returns the symbols the SDK defines without explicit project settings.
     *
     * Includes `DEBUG`, `TRACE`, `NET`, `NETCOREAPP`, and the target framework's
     * symbols such as `NET10_0` and `NET10_0_OR_GREATER`.
     *
     * The older `NETx_y_OR_GREATER` symbols are not added.
     */
    fun implicitDefines(info: CsprojInfo): List<String> = buildList {
        add("DEBUG"); add("TRACE")
        val version = Regex("""^net(\d+)\.(\d+)$""").matchEntire(info.targetFramework ?: "net10.0") ?: return@buildList
        val (major, minor) = version.destructured
        add("NET"); add("NETCOREAPP")
        add("NET${major}_$minor"); add("NET${major}_${minor}_OR_GREATER")
    }

    /**
     * Returns the compiler arguments for the project.
     *
     * [forceLibrary] compiles without an entry point, which is useful when checking
     * individual files that do not need to contain `Main`.
     */
    fun arguments(
        info: CsprojInfo,
        sources: List<Path>,
        references: List<Path>,
        out: Path,
        globalUsings: Path?,
        forceLibrary: Boolean = false,
        mainClass: String? = null,
        assemblyInfo: Path? = null,
        resources: List<Pair<Path, String>> = emptyList(),
    ): List<String> = buildList {
        add("-nologo"); add("-noconfig")
        add(if (forceLibrary || info.isLibrary) "-target:library" else "-target:exe")
        info.langVersion?.let { add("-langversion:$it") }
        (info.nullableMode ?: if (info.nullable) "enable" else null)
            ?.takeIf { it in NULLABLE_MODES }?.let { add("-nullable:$it") }
        // Select the entry point when the project has more than one Main method.
        if (mainClass != null && !forceLibrary && !info.isLibrary) add("-main:$mainClass")
        val defines = (implicitDefines(info) + info.defines).distinct()
        if (defines.isNotEmpty()) add("-define:" + defines.joinToString(";"))
        if (info.allowUnsafe) add("-unsafe")
        if (info.checkedArithmetic) add("-checked")
        if (info.optimize) add("-optimize+")
        info.warningLevel?.let { add("-warn:$it") }
        if (info.treatWarningsAsErrors) add("-warnaserror")
        if (info.warningsAsErrors.isNotEmpty()) {
            add("-warnaserror:" + info.warningsAsErrors.joinToString(",") { number(it) })
        }
        if (info.warningsNotAsErrors.isNotEmpty()) {
            add("-warnaserror-:" + info.warningsNotAsErrors.joinToString(",") { number(it) })
        }
        if (info.noWarn.isNotEmpty()) add("-nowarn:" + info.noWarn.joinToString(",") { number(it) })
        // Only a build writes output beyond the DLL; editor checks compile into a scratch folder.
        if (!forceLibrary) {
            CsprojExtras.debugFlag(info)?.let { add(it) }
            CsprojExtras.platformFlag(info)?.let { add(it) }
            if (info.generateDocumentation) add("-doc:" + out.resolveSibling(CsprojExtras.documentationName(out)))
            resources.forEach { (file, name) -> add("-resource:$file,$name") }
        }
        add("-out:$out")
        references.forEach { add("-r:$it") }
        sources.forEach { add(it.toString()) }
        globalUsings?.let { add(it.toString()) }
        assemblyInfo?.let { add(it.toString()) }
    }

    private fun logSummary(file: Path, info: CsprojInfo) {
        Log.d(
            TAG,
            "csproj: ${file.fileName}: ${info.outputType}, ${info.includes.size} include, " +
                "${info.removes.size} remove, ${info.projectReferences.size} project ref, " +
                "${info.packageReferences.size} package ref, target ${info.targetFramework}, " +
                "AssemblyName=${info.assemblyName}, OutDir=${info.outDir}, in $file, " +
                "references ${info.references.joinToString { "${it.name}(copy=${it.copyLocal})" }}",
        )
    }
}

/**
 * Converts a project-file glob into a regex matching `/`-separated paths relative to the project.
 *
 * `**` can cross directory boundaries; `*` and `?` cannot.
 */
internal fun globToRegex(glob: String): Regex {
    val normalized = glob.replace('\\', '/').removePrefix("./")
    val pattern = StringBuilder()
    var i = 0
    while (i < normalized.length) {
        when {
            normalized.startsWith("**/", i) -> { pattern.append("(?:.*/)?"); i += 3 }
            normalized.startsWith("**", i) -> { pattern.append(".*"); i += 2 }
            normalized[i] == '*' -> { pattern.append("[^/]*"); i++ }
            normalized[i] == '?' -> { pattern.append("[^/]"); i++ }
            else -> { pattern.append(Regex.escape(normalized[i].toString())); i++ }
        }
    }
    return Regex(pattern.toString())
}