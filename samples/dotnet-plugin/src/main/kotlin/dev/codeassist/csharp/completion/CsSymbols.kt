package dev.codeassist.csharp.completion


import android.util.Log
import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.project.csproj.CsprojInfo
import dev.codeassist.csharp.sdk.CsCompilerHost
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.codeassist.csharp.sdk.ensureRuntimeConfig
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.ToolCancellation
import dev.codeassist.csharp.util.killableTool
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads public types and members from referenced DLLs using a small C# tool.
 *
 * The tool uses System.Reflection.Metadata and runs through the same launcher as the program.
 * It is built once with the resident compiler into <home>/tools/ and its output is cached
 * until a DLL changes.
 *
 * Output is one tab-separated symbol per line. A namespace of "-" means no namespace:
 *   type<TAB>ns<TAB>name<TAB>class|interface<TAB>static 0|1
 *   member<TAB>ns<TAB>type<TAB>method|field|property<TAB>name<TAB>static 0|1[<TAB>parameter count]
 *
 * The parameter count appears only on method lines and is used by completion to place the caret.
 */
internal class CsSymbols(private val sdk: DotnetSdk) {

    private var cacheKey: String? = null
    private var cached: List<String> = emptyList()

    /** Last built table for each module directory, available to synchronous quick fixes. */
    private val tables = java.util.concurrent.ConcurrentHashMap<Path, CsSymbolTable>()

    private var projectKey: String? = null
    private var projectDll: Path? = null
    private var projectOwner: Path? = null

    // Symbol lookup

    /** Returns the symbol lines for [dlls], cached by DLL paths and modification times. */
    suspend fun linesFor(dlls: List<Path>): List<String> {
        if (dlls.isEmpty()) return emptyList()

        val key = stampOf(dlls)
        return killableTool { cancellation -> runTool(key, dlls, cancellation) }
    }

    /**
     * Builds the symbol table from project sources, referenced DLLs, and the SDK reference assemblies.
     *
     * [info] is null for modules without a .csproj. Such modules use their own sources and the
     * base class library, without additional project references.
     */
    suspend fun tableFor(
        info: CsprojInfo?,
        dir: Path,
        sources: List<Path>,
    ): CsSymbolTable {
        val references = info?.let {
            Csproj.resolveReferences(it, dir, sdk.nuget, sdk.frameworkDllNames()).first
        } ?: emptyList()

        val bcl = sdk.references()
        val project = projectLibrary(info, dir, sources, references + bcl)
        val table = CsSymbolTable(linesFor(bcl + references + listOfNotNull(project)))

        tables[dir.toAbsolutePath().normalize()] = table
        return table
    }

    /** Returns the last table built for [dir], without running the symbol tool. */
    fun cachedTable(dir: Path): CsSymbolTable? =
        tables[dir.toAbsolutePath().normalize()]

    // Project assembly

    /**
     * Compiles the project's sources into a library the symbol tool can inspect.
     *
     * Recompiles when a source file's modification time changes. If compilation fails,
     * the last successfully compiled library is retained. Sources are read from disk,
     * not from the editor's unsaved buffer.
     */
    suspend fun projectLibrary(
        info: CsprojInfo?,
        dir: Path,
        sources: List<Path>,
        references: List<Path>,
    ): Path? {
        if (sources.isEmpty()) return null

        val key = stampOf(sources)
        return killableTool { cancellation ->
            compileProject(key, info, dir, sources, references, cancellation)
        }
    }

    @Synchronized
    private fun compileProject(
        key: String,
        info: CsprojInfo?,
        dir: Path,
        sources: List<Path>,
        references: List<Path>,
        cancellation: ToolCancellation,
    ): Path? {
        if (key == projectKey && projectOwner == dir) return projectDll

        val work = sdk.home.resolve("symbols")
        Files.createDirectories(work)

        val out = work.resolve("project-" + Integer.toHexString(dir.toString().hashCode()) + ".dll")
        val usingsText = Csproj.globalUsingsText(info)
        val usings = work.resolve("GlobalUsings.g.cs").takeIf { usingsText.isNotEmpty() }

        usings?.let {
            Files.write(it, usingsText.toByteArray(Charsets.UTF_8))
        }

        val arguments = if (info != null) {
            Csproj.arguments(info, sources, references, out, usings, forceLibrary = true)
        } else {
            // Use the same plain library arguments as the editor's check without a project file.
            buildList {
                add("-nologo")
                add("-noconfig")
                add("-target:library")
                add("-out:$out")
                references.forEach { add("-r:$it") }
                sources.forEach { add(it.toString()) }
            }
        }

        val exitCode = CsCompilerHost.compile(sdk, arguments, cancellation) {
            Log.d(TAG, "project types> $it")
        }
        Log.d(TAG, "project types: compile exit $exitCode")

        if (exitCode == 0 && Files.isRegularFile(out)) {
            projectKey = key
            projectDll = out
            projectOwner = dir
        }

        return projectDll?.takeIf { projectOwner == dir }
    }

    // Symbol tool

    /** Builds a cache key from each file path and its last modification time. */
    private fun stampOf(paths: List<Path>): String =
        paths.joinToString("|") { path ->
            "$path@" + runCatching {
                Files.getLastModifiedTime(path).toMillis()
            }.getOrDefault(0L)
        }

    /** Runs the symbol tool when [key] differs from the last successful run. */
    @Synchronized
    private fun runTool(
        key: String,
        dlls: List<Path>,
        cancellation: ToolCancellation,
    ): List<String> {
        if (key == cacheKey) return cached

        val launcher = sdk.launcher ?: return emptyList()
        val tool = ensureTool(cancellation) ?: return emptyList()

        val process = ProcessBuilder(
            listOf(launcher.toString(), tool.toString()) + dlls.map { it.toString() }
        )
            .redirectErrorStream(true)
            .apply { environment().putAll(sdk.environment()) }
            .start()

        cancellation.attach(process)
        runCatching { process.outputStream.close() }

        val output = process.inputStream.bufferedReader().readLines()
        val exitCode = process.waitFor()

        Log.d(TAG, "symbols: tool exit $exitCode, ${output.size} lines for ${dlls.size} dll(s)")

        if (exitCode != 0) return emptyList()

        cacheKey = key
        cached = output.filter {
            it.startsWith("type\t") || it.startsWith("member\t")
        }

        return cached
    }

    /** Builds the symbol tool when its source changes or its DLL is missing. */
    private fun ensureTool(cancellation: ToolCancellation): Path? {
        val dir = sdk.home.resolve("tools")
        val source = dir.resolve("SymbolDump.cs")
        val dll = dir.resolve("SymbolDump.dll")

        val current = runCatching {
            String(Files.readAllBytes(source), Charsets.UTF_8)
        }.getOrNull()

        if (current == TOOL_SOURCE && Files.isRegularFile(dll)) {
            ensureRuntimeConfig(dir.resolve("SymbolDump.runtimeconfig.json"))
            return dll
        }

        Files.createDirectories(dir)
        Files.write(source, TOOL_SOURCE.toByteArray(Charsets.UTF_8))
        ensureRuntimeConfig(dir.resolve("SymbolDump.runtimeconfig.json"))
        Files.deleteIfExists(dll)

        val arguments = buildList {
            add("-nologo")
            add("-noconfig")
            add("-target:exe")
            add("-out:$dll")
            sdk.references().forEach { add("-r:$it") }
            add(source.toString())
        }

        val code = CsCompilerHost.compile(sdk, arguments, cancellation) {
            Log.d(TAG, "symbols tool> $it")
        }

        if (code != 0 || !Files.isRegularFile(dll)) {
            Log.d(TAG, "symbols: the tool did not build (exit $code)")
            return null
        }

        return dll
    }

    private companion object {

        /** No string interpolation is used in this embedded C# source. */
        val TOOL_SOURCE = """using System;
        using System.IO;
        using System.Reflection;
        using System.Reflection.Metadata;
        using System.Reflection.Metadata.Ecma335;
        using System.Reflection.PortableExecutable;

        static class SymbolDump
        {
            static int Main(string[] args)
            {
                foreach (string path in args)
                {
                    using (var stream = File.OpenRead(path))
                    using (var pe = new PEReader(stream))
                    {
                        MetadataReader reader = pe.GetMetadataReader();
                        foreach (TypeDefinitionHandle handle in reader.TypeDefinitions)
                        {
                            TypeDefinition type = reader.GetTypeDefinition(handle);
                            if ((type.Attributes & TypeAttributes.VisibilityMask) != TypeAttributes.Public) continue;
                            string rawName = reader.GetString(type.Name);
                            if (rawName == "<Module>") continue;

                            string ns = reader.GetString(type.Namespace);
                            if (ns.Length == 0) ns = "-";
                            string name = rawName.Split('`')[0];
                            string kind = (type.Attributes & TypeAttributes.Interface) != 0 ? "interface" : "class";
                            bool isStatic = (type.Attributes & TypeAttributes.Abstract) != 0 && (type.Attributes & TypeAttributes.Sealed) != 0;
                            Console.WriteLine("type\t" + ns + "\t" + name + "\t" + kind + "\t" + (isStatic ? "1" : "0"));

                            foreach (MethodDefinitionHandle mh in type.GetMethods())
                            {
                                MethodDefinition m = reader.GetMethodDefinition(mh);
                                if ((m.Attributes & MethodAttributes.Public) == 0) continue;
                                if ((m.Attributes & MethodAttributes.SpecialName) != 0) continue;
                                string mname = reader.GetString(m.Name);
                                bool mstatic = (m.Attributes & MethodAttributes.Static) != 0;
                                int pcount = 0;
                                foreach (ParameterHandle ph in m.GetParameters())
                                {
                                    if (reader.GetParameter(ph).SequenceNumber > 0) pcount++;
                                }
                                Console.WriteLine("member\t" + ns + "\t" + name + "\tmethod\t" + mname + "\t" + (mstatic ? "1" : "0") + "\t" + pcount);
                            }

                            foreach (FieldDefinitionHandle fh in type.GetFields())
                            {
                                FieldDefinition f = reader.GetFieldDefinition(fh);
                                if ((f.Attributes & FieldAttributes.Public) == 0) continue;
                                string fname = reader.GetString(f.Name);
                                bool fstatic = (f.Attributes & FieldAttributes.Static) != 0;
                                Console.WriteLine("member\t" + ns + "\t" + name + "\tfield\t" + fname + "\t" + (fstatic ? "1" : "0"));
                            }

                            foreach (PropertyDefinitionHandle ph in type.GetProperties())
                            {
                                PropertyDefinition p = reader.GetPropertyDefinition(ph);
                                PropertyAccessors accessors = p.GetAccessors();
                                if (accessors.Getter.IsNil) continue;
                                MethodDefinition getter = reader.GetMethodDefinition(accessors.Getter);
                                if ((getter.Attributes & MethodAttributes.Public) == 0) continue;
                                bool pstatic = (getter.Attributes & MethodAttributes.Static) != 0;
                                string pname = reader.GetString(p.Name);
                                Console.WriteLine("member\t" + ns + "\t" + name + "\tproperty\t" + pname + "\t" + (pstatic ? "1" : "0"));
                            }
                        }
                    }
                }
                return 0;
            }
        }
    """
    }
}
