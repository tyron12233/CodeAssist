package dev.codeassist.ndk

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.DiagnosticProvider
import dev.ide.lang.LanguageId
import dev.ide.analysis.Diagnostic
import dev.ide.platform.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * Editor errors for C and C++, produced by running the compiler that will build the file.
 *
 * A [DiagnosticProvider] rather than an `Analyzer` because this one has to leave the thread. An analyzer's
 * `analyze` is synchronous and runs inside the framework's read action, which is the right shape for a
 * parser already in memory and the wrong one for a process launch that takes a few hundred milliseconds. A
 * provider may suspend, so the compiler runs on IO and the editor stays responsive.
 *
 * There is no language server here and no second parser: the diagnostics the editor underlines come from the
 * same clang that the build runs, so the two cannot drift apart. What it costs is latency, which is why the
 * result is cached against the buffer's exact text — the analysis engine re-runs providers on a schedule of
 * its own, and re-compiling an unchanged buffer would be pure heat.
 */
class NdkDiagnosticProvider(
    private val toolchain: () -> NdkToolchain?,
    private val log: Logger,
) : DiagnosticProvider {

    override val id = "ndk.clang"

    /**
     * Named, not empty. An empty set means "every language", which here would run a C++ compiler over
     * Kotlin; it is also what makes the host treat C and C++ as analysable at all, since a language with
     * neither a backend nor anything claiming it is never given a target.
     */
    override val languages = setOf(LanguageId(NdkPlugin.C_LANGUAGE), LanguageId(NdkPlugin.CPP_LANGUAGE))

    /** The last result, keyed on the buffer it came from. One entry: the user edits one file at a time. */
    private var cachedKey: Pair<String, Int>? = null
    private var cached: List<Diagnostic> = emptyList()

    override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> {
        val toolchain = toolchain() ?: return emptyList()
        val path = runCatching { Paths.get(target.file.path) }.getOrNull() ?: return emptyList()
        val text = target.parsed.text().toString()

        // Keyed on the text's hash rather than the document version: a provider can be asked again for a
        // buffer that changed and changed back (an undo), and re-running the compiler for a result we hold
        // is the one cost worth avoiding here.
        // The module's own configuration, so the editor compiles the file the way the build will: the C++
        // standard it was created with, the extra flags it names, and -- for a NativeActivity -- the glue's
        // include directory, without which line 2 of every generated project is a false error.
        val facet = target.module.facets.get(NdkFacet.KEY) ?: NdkFacet()
        NdkFlags.remember(target.module.dir.path, facet)

        // The facet is part of the key: changing the standard changes what parses, and answering from a
        // cache filled under the old one would leave the editor a version behind the file.
        val key = target.file.path to (text.hashCode() * 31 + facet.hashCode())
        synchronized(this) { if (cachedKey == key) return cached }

        val cpp = NdkPlugin.CPP_SUFFIXES.any { path.fileName.toString().endsWith(it) }
        val result = withContext(Dispatchers.IO) {
            // Preparing is cheap after the first call and unavoidable before it: this is usually the first
            // thing in a session that needs a compiler, and an unprepared toolchain has no sysroot to
            // compile against.
            when (toolchain.prepare()) {
                is NdkToolchain.Status.Unavailable -> null
                is NdkToolchain.Status.Ready ->
                    toolchain.syntaxCheck(path, text, cpp, NdkFlags.editor(facet, toolchain, cpp))
            }
        } ?: return emptyList()

        target.checkCanceled()

        val diagnostics = runCatching { ClangDiagnostics.parse(result.output, text) }
            .onFailure { log.warn("could not read clang's diagnostics", it) }
            .getOrDefault(emptyList())

        // A compiler that exits 0 with nothing to say is the common case and means a clean file. One that
        // fails while reporting nothing parseable is a problem with the toolchain, not the user's code, and
        // saying so in the log beats inventing a diagnostic on their file.
        if (!result.ok && diagnostics.isEmpty()) {
            log.warn("clang failed with no diagnostics for ${target.file.path}: ${result.output.take(300)}")
        }

        synchronized(this) {
            cachedKey = key
            cached = diagnostics
        }
        return diagnostics
    }
}
