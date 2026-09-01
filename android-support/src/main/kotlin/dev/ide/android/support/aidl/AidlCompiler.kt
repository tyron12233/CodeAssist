package dev.ide.android.support.aidl

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * What to compile and what to compile it against.
 *
 * The split between [sourceRoots] and [importRoots] mirrors the reference compiler's `-I`: files under the
 * source roots are *generated from*, files under the import roots only contribute type declarations. That is
 * how a `parcelable` declared by a dependency (or shipped in an AAR's `aidl/` folder) becomes referenceable
 * without that dependency's stubs being generated a second time into this module.
 */
data class AidlCompileRequest(
    /** The module's own `aidl/` content roots. Every `.aidl` below these is compiled. */
    val sourceRoots: List<Path>,
    /** Dependency and AAR `aidl/` folders: parsed for the types they declare, never generated from. */
    val importRoots: List<Path> = emptyList(),
    /** `platforms/android-NN/framework.aidl`: the SDK's preprocessed list of framework AIDL types, when present. */
    val frameworkAidl: Path? = null,
    /** Compile classpath, used to classify a framework type when no `framework.aidl` is available (on-device). */
    val classpath: List<Path> = emptyList(),
    /** Where the generated `.java` goes; laid out by package, like any source root. */
    val outputDir: Path,
)

/** Generated files plus everything worth telling the user about. */
data class AidlCompileResult(
    val generated: List<Path>,
    val diagnostics: List<AidlDiagnostic>,
) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == AidlSeverity.ERROR }
}

/**
 * The AIDL compiler: `.aidl` in, `.java` out.
 *
 * This is a full reimplementation rather than a call into the SDK's `aidl` binary, because that binary ships
 * only as a linux-x86_64 executable in `build-tools`, so there is nothing to run on an Android device, which is
 * where this IDE builds. One Kotlin implementation serves the desktop build, the on-device build, and the
 * editor's pre-build resolution alike.
 */
object AidlCompiler {

    /** Parse, resolve and generate. Never throws: syntax errors come back as [AidlDiagnostic]s. */
    fun compile(request: AidlCompileRequest): AidlCompileResult {
        val diagnostics = ArrayList<AidlDiagnostic>()
        val sources = request.sourceRoots.flatMap { root -> aidlFilesUnder(root).map { root to it } }
        if (sources.isEmpty()) return AidlCompileResult(emptyList(), diagnostics)

        val parsedSources = sources.mapNotNull { (root, path) ->
            parse(path, diagnostics, AidlSeverity.ERROR)?.also { checkPackageMatchesPath(it, root, path, diagnostics) }
        }
        // A dependency's file failing to parse is not this module's fault and must not fail its build; the
        // types it would have contributed simply go unresolved, which reports itself precisely at the use site.
        val parsedImports = request.importRoots.flatMap { aidlFilesUnder(it) }
            .mapNotNull { parse(it, diagnostics, AidlSeverity.WARNING) }
        val framework = request.frameworkAidl
            ?.takeIf { Files.isRegularFile(it) }
            ?.let { parse(it, diagnostics, AidlSeverity.WARNING) }

        return AidlClasspathProbe.over(request.classpath).use { probe ->
            val table = AidlTypeTable.of(parsedSources + parsedImports + listOfNotNull(framework), probe = probe)
            val generated = ArrayList<Path>()
            // Regenerate the output root from scratch so a renamed or deleted .aidl leaves no orphan .java
            // behind on the compile source path.
            clear(request.outputDir)
            for (file in parsedSources) {
                for (decl in file.declarations) {
                    val java = AidlJavaGenerator.generate(file, decl, table, diagnostics::add) ?: continue
                    val target = request.outputDir.resolve(java.relativePath)
                    Files.createDirectories(target.parent)
                    target.writeText(java.source)
                    generated.add(target)
                }
            }
            AidlCompileResult(generated, diagnostics)
        }
    }

    /** Every `.aidl` file below [root], or nothing when the root does not exist. */
    fun aidlFilesUnder(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".aidl") }
                .collect(Collectors.toList())
        }.sorted()
    }

    /** Parse one file, reporting a failure at [onFailure] severity and returning null. */
    fun parse(path: Path, diagnostics: MutableList<AidlDiagnostic>, onFailure: AidlSeverity): AidlFile? = try {
        AidlParser.parse(path.readText(), path.toString())
    } catch (e: AidlSyntaxException) {
        diagnostics.add(AidlDiagnostic(onFailure, e.message.orEmpty(), path.toString(), e.pos))
        null
    } catch (e: java.io.IOException) {
        diagnostics.add(AidlDiagnostic(onFailure, "cannot read AIDL file: ${e.message}", path.toString()))
        null
    }

    /**
     * AIDL resolves an unqualified name through the *directory* an import points at, so `com.example.IFoo`
     * has to live in `com/example/IFoo.aidl`. A mismatch still generates here (the `package` statement wins),
     * but it will not resolve from another file, so say so rather than letting the import fail mysteriously.
     */
    private fun checkPackageMatchesPath(
        file: AidlFile,
        root: Path,
        path: Path,
        diagnostics: MutableList<AidlDiagnostic>,
    ) {
        val expected = root.relativize(path).parent?.toString()?.replace(java.io.File.separatorChar, '.').orEmpty()
        if (expected == file.packageName) return
        diagnostics.add(
            AidlDiagnostic(
                AidlSeverity.WARNING,
                "package '${file.packageName}' does not match the file's location under the aidl root " +
                    "(expected '${expected.ifEmpty { "the root directory" }}'). Other .aidl files will not be able to import it.",
                path.toString(),
                AidlPos(1, 1),
            )
        )
    }

    private fun clear(dir: Path) {
        if (Files.isDirectory(dir)) {
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        Files.createDirectories(dir)
    }
}
