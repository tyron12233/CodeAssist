package dev.codeassist.ndk.jni

import dev.codeassist.ndk.NdkFacet
import dev.codeassist.ndk.NdkPlugin
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.platform.ContentHash
import dev.ide.platform.log.Logger
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Paths

private val SOURCE = DiagnosticSource.Analyzer(AnalyzerId("ndk.jni"))

/**
 * Flags a `native` method (Java) or `external fun` (Kotlin) that no C or C++ function in the module
 * implements, and offers to write the function.
 *
 * Without it the mistake has one symptom, at runtime, on the first call: `UnsatisfiedLinkError: No
 * implementation found for … stringFromJNI()`. Only modules with the NDK facet are checked, since a module
 * without one gets its native code from somewhere this plugin cannot see (a prebuilt `.so`, another module),
 * and a module that calls `RegisterNatives` binds by its own table rather than by name.
 */
class JniMissingFunctionProvider(private val log: Logger) : DiagnosticProvider {
    override val id = "ndk.jni.missing"
    override val languages = setOf(LanguageId("java"), LanguageId("kotlin"))

    override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> = runCatching {
        JniModules.remember(target.module)
        val facet = target.module.facets.get(NdkFacet.KEY) ?: return emptyList()
        val path = target.file.path
        val snapshot = JniIndex.snapshot(target.module, facet, path, target.parsed.text().toString())
        if (snapshot.registersNatives) return emptyList()
        snapshot.natives.filter { it.path == path && !snapshot.isImplemented(it.method) }.map { declared ->
            val m = declared.method
            val symbol = Jni.preferredName(m, snapshot.siblingsOf(m))
            Diagnostic(
                range = TextRange(m.nameOffset, m.nameOffset + m.name.length),
                severity = Severity.WARNING,
                message = "No JNI function implements '${m.name}': calling it throws UnsatisfiedLinkError. " +
                    "Expected $symbol in the module's C/C++ sources.",
                source = SOURCE,
                code = "ndk.jni.missing",
                fixes = listOfNotNull(CreateJniFunctionFix.forMethod(m, symbol, snapshot, target, facet)),
            )
        }
    }.getOrElse {
        log.warn("JNI check failed on ${target.file.path}", it)
        emptyList()
    }
}

/**
 * Flags a `Java_…` C/C++ function that no native method in the module declares: usually the leftover of a
 * rename on the Java side, or a typo in a hand-written name, and dead code either way, since nothing will
 * ever call it. Skipped in a module that declares no native methods at all, whose Java side is elsewhere.
 */
class JniOrphanFunctionProvider(private val log: Logger) : DiagnosticProvider {
    override val id = "ndk.jni.orphan"
    override val languages = setOf(LanguageId(NdkPlugin.C_LANGUAGE), LanguageId(NdkPlugin.CPP_LANGUAGE))

    override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> = runCatching {
        JniModules.remember(target.module)
        val facet = target.module.facets.get(NdkFacet.KEY) ?: return emptyList()
        val path = target.file.path
        val snapshot = JniIndex.snapshot(target.module, facet, path, target.parsed.text().toString())
        if (snapshot.natives.isEmpty()) return emptyList()
        snapshot.functions.filter { it.path == path && snapshot.declarationOf(it.function) == null }.map { defined ->
            val f = defined.function
            Diagnostic(
                range = TextRange(f.nameOffset, f.nameOffset + f.name.length),
                severity = Severity.WARNING,
                message = "No native method in this module matches ${f.name}, so nothing calls it. " +
                    "Was the Java or Kotlin method renamed or moved?",
                source = SOURCE,
                code = "ndk.jni.orphan",
            )
        }
    }.getOrElse {
        log.warn("JNI check failed on ${target.file.path}", it)
        emptyList()
    }
}

/**
 * Writes the C/C++ function a native method is missing: appended to the module's main C/C++ file, or, in a
 * module that has none yet, into a new one named after the library.
 */
class CreateJniFunctionFix private constructor(
    override val title: String,
    private val method: NativeMethod,
    private val symbol: String,
    private val file: String,
    private val exists: Boolean,
) : QuickFix {
    override val kind = CodeActionKind.QUICK_FIX

    override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
        val cpp = !file.endsWith(".c")
        val stub = Jni.stub(method, symbol, cpp)
        if (!exists) {
            return WorkspaceEdit.createFile(file, "#include <jni.h>\n\n$stub")
        }
        val text = runCatching { String(Files.readAllBytes(Paths.get(file))) }.getOrNull() ?: return WorkspaceEdit.EMPTY
        val edits = ArrayList<DocumentEdit>()
        if (!Regex("""#\s*include\s*[<"]jni\.h[>"]""").containsMatchIn(text)) edits.add(DocumentEdit(0, 0, "#include <jni.h>\n"))
        val separator = when {
            text.isEmpty() -> ""
            text.endsWith("\n\n") -> ""
            text.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        edits.add(DocumentEdit(text.length, 0, separator + stub))
        return WorkspaceEdit(mapOf(ExistingFile(file) to edits))
    }

    companion object {
        /**
         * The fix for [method], or null when there is nowhere it could go on this host: the module has no C/C++
         * file yet and the host predates file creation from a fix (SPI 3.1.0).
         */
        fun forMethod(
            method: NativeMethod,
            symbol: String,
            snapshot: JniSnapshot,
            target: AnalysisTarget,
            facet: NdkFacet,
        ): CreateJniFunctionFix? {
            val classPrefix = "Java_" + Jni.mangle(method.className.replace('.', '/')) + "_"
            val existing = snapshot.cppFiles.firstOrNull { it.substringAfterLast('/').substringBeforeLast('.') == facet.libraryName }
                ?: snapshot.functions.firstOrNull { it.function.name.startsWith(classPrefix) }?.path?.takeIf { it in snapshot.cppFiles }
                ?: snapshot.cppFiles.firstOrNull()
            if (existing != null) {
                return CreateJniFunctionFix("Create JNI function in ${existing.substringAfterLast('/')}", method, symbol, existing, exists = true)
            }
            if (!HostSupport.fixesCreateFiles) return null
            val dir = Paths.get(target.module.dir.path).resolve(facet.sourceDirs.firstOrNull() ?: "src/main/cpp")
            val path = dir.resolve("${facet.libraryName}.cpp").toString()
            return CreateJniFunctionFix("Create ${facet.libraryName}.cpp with the JNI function", method, symbol, path, exists = false)
        }
    }
}

/** What the running IDE supports of the SPI this plugin was built against, found by trying it once. */
internal object HostSupport {
    /**
     * Whether the host carries SPI 3.1.0, told by a class that release added. Loaded through this plugin's
     * own class loader, whose parent is the host's: the plugin compiles against the SPI and never bundles it.
     */
    val spi31: Boolean by lazy {
        runCatching { Class.forName("dev.ide.analysis.NavigationTarget", false, HostSupport::class.java.classLoader) }.isSuccess
    }

    /** `WorkspaceEdit.createFile`, SPI 3.1.0. */
    val fixesCreateFiles: Boolean by lazy {
        try {
            WorkspaceEdit.createFile("/dev/null/probe", "")
            true
        } catch (e: LinkageError) {
            false
        }
    }
}

/** A file on disk that a [WorkspaceEdit] edits. The host reads only its [path]. */
internal class ExistingFile(override val path: String) : VirtualFile {
    override val name: String get() = path.substringAfterLast('/')
    override val isDirectory: Boolean get() = false
    override val exists: Boolean get() = true
    override val length: Long get() = runCatching { Files.size(Paths.get(path)) }.getOrDefault(0L)
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash(): ContentHash = ContentHash.of(readBytes())
    override fun readBytes(): ByteArray = runCatching { Files.readAllBytes(Paths.get(path)) }.getOrDefault(ByteArray(0))
    override fun readText(): CharSequence = String(readBytes())
    override fun equals(other: Any?): Boolean = other is ExistingFile && other.path == path
    override fun hashCode(): Int = path.hashCode()
}
