package dev.ide.lang.jdt.compile

import dev.ide.lang.jdt.analysis.JavaProblemCodes
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jdt.core.compiler.CategorizedProblem
import org.eclipse.jdt.internal.compiler.CompilationResult
import org.eclipse.jdt.internal.compiler.ICompilerRequestor
import org.eclipse.jdt.internal.compiler.batch.Main

/**
 * Compiles Java sources to `.class` with the Eclipse batch compiler (ecj) — the JDT compile backend.
 * No reflection, no hosting `javac`. The signature is JDT-free (paths + a plain result) so callers (the
 * build engine, tests) don't depend on JDT internals.
 *
 * **Bootclasspath.** ecj normally borrows the running VM's platform classes (`rt.jar` on Java 8, the
 * `jrt:` jimage on Java 9+) as the implicit JRE library, so on a desktop JDK only [classpath] (module
 * outputs + library jars) need be supplied. **On ART (Dalvik) there is no such ecj-readable image** —
 * the platform lives in the boot `.oat`/`.art`/dex, which ecj cannot load — so with no explicit
 * `-bootclasspath` ecj finds *no* platform types and fails with `"The type java.lang.Object cannot be
 * resolved. It is indirectly referenced from required .class files."` For Android compilation the boot
 * library should be `android.jar` anyway (the user's code targets the SDK, not the host JDK), so callers
 * pass it via [bootClasspath]; when they don't, we fall back to the [classpath] on ART (it carries
 * `android.jar`, which holds `java.lang.Object`). Passing `-bootclasspath` also stops ecj from probing
 * the host VM's (absent) jimage. Desktop behaviour is unchanged — there `bootClasspath` is empty and the
 * runtime is not Dalvik, so ecj keeps using the host JDK's platform classes.
 */
object JdtBatchCompiler {

    /**
     * One compiler problem, taken from ecj's own [CategorizedProblem] rather than scraped out of its
     * printed report: [file]/[line]/[column] locate it, [code] is the stable id behind its ecj problem id
     * (see [dev.ide.lang.jdt.analysis.JavaProblemCodes]) so a keyed quick fix can attach to it, and
     * [snippet] is the offending source line.
     *
     * [column] is -1 and [code]/[snippet] are null only when the problem came from the text fallback
     * ([parseEcjDiagnostics]), which cannot recover them.
     */
    data class Diagnostic(
        val file: String?,
        val line: Int?,
        val message: String,
        val isError: Boolean,
        val column: Int = -1,
        val code: String? = null,
        val snippet: String? = null,
    )

    data class Result(
        val success: Boolean,
        /** Raw compiler output lines (ecj's textual report or the GNU-shaped lines), for the transcript. */
        val messages: List<String>,
        /** The problems themselves. This — not [messages] — is what a caller presents; see [Diagnostic]. */
        val diagnostics: List<Diagnostic> = emptyList(),
    )

    /** Build a [Diagnostic] from an ecj problem, computing the column and snippet off the unit's contents. */
    internal fun diagnosticOf(p: CategorizedProblem, result: CompilationResult?): Diagnostic = Diagnostic(
        file = runCatching { String(p.originatingFileName) }.getOrNull(),
        line = p.sourceLineNumber.takeIf { it > 0 },
        message = p.message,
        isError = p.isError,
        column = columnOf(p, result),
        code = JavaProblemCodes.codeFor(p.id),
        snippet = snippetOf(p, result),
    )

    /**
     * 1-based column of [p]'s start. ecj reports offsets, not columns; [CompilationResult.lineSeparatorPositions]
     * holds the offset of each line's terminator, so the start of line N is one past separator N-1.
     */
    private fun columnOf(p: CategorizedProblem, result: CompilationResult?): Int {
        val seps = result?.lineSeparatorPositions ?: return -1
        val line = p.sourceLineNumber
        if (line <= 1) return p.sourceStart + 1
        val prev = line - 2
        if (prev !in seps.indices) return -1
        return (p.sourceStart - seps[prev]).coerceAtLeast(1)
    }

    /** The source line the problem sits on, so a console can show the offending code without opening the file. */
    private fun snippetOf(p: CategorizedProblem, result: CompilationResult?): String? = runCatching {
        val contents = result?.compilationUnit?.contents ?: return null
        val seps = result.lineSeparatorPositions ?: return null
        val line = p.sourceLineNumber
        val start = if (line <= 1) 0 else seps.getOrNull(line - 2)?.plus(1) ?: return null
        val end = seps.getOrNull(line - 1) ?: contents.size
        if (start !in 0..end || end > contents.size) return null
        String(contents, start, end - start).trim().ifEmpty { null }
    }.getOrNull()

    private fun IntArray.getOrNull(i: Int): Int? = if (i in indices) this[i] else null

    // `<n>. ERROR|WARNING in <file> (at line <n>)` — ecj's textual diagnostic header.
    private val ECJ_HEADER = Regex("""^\d+\.\s+(ERROR|WARNING)\s+in\s+(.+?)\s+\(at line (\d+)\)""")
    // ecj's trailing summary, e.g. `1 problem (1 error)` — a boundary, never a diagnostic.
    private val ECJ_SUMMARY = Regex("""^\d+\s+problems?\b""")

    /**
     * Parse ecj's textual report into structured [Diagnostic]s. Each problem is a header line followed by the
     * offending source snippet + caret (both indented) and then the description (flush-left); blocks are
     * separated by `----------`. We key off the header and take the flush-left, non-summary lines as the message
     * — so callers get the *actual* error text, not just the header (which is all a naive `contains("ERROR")` keeps).
     */
    fun parseEcjDiagnostics(text: String): List<Diagnostic> {
        val lines = text.lines()
        val out = ArrayList<Diagnostic>()
        var i = 0
        while (i < lines.size) {
            val header = ECJ_HEADER.find(lines[i].trim())
            if (header == null) { i++; continue }
            val isError = header.groupValues[1] == "ERROR"
            val file = header.groupValues[2]
            val line = header.groupValues[3].toIntOrNull()
            i++
            val message = StringBuilder()
            while (i < lines.size) {
                val raw = lines[i]
                val trimmed = raw.trim()
                if (trimmed == "----------" || ECJ_HEADER.containsMatchIn(trimmed) || ECJ_SUMMARY.containsMatchIn(trimmed)) break
                // Snippet + caret lines are indented; the description is flush-left.
                if (trimmed.isNotEmpty() && !raw[0].isWhitespace()) {
                    if (message.isNotEmpty()) message.append(' ')
                    message.append(trimmed)
                }
                i++
            }
            out.add(Diagnostic(file, line, message.toString().ifBlank { if (isError) "error" else "warning" }, isError))
        }
        return out
    }

    /** Compliance level parsed from an ecj `-source`/`-target` string (`"8"`, `"11"`, `"1.8"`, …) is >= 9. */
    private fun complianceAtLeast9(level: String): Boolean {
        val n = level.removePrefix("1.").takeWhile { it.isDigit() }.toIntOrNull() ?: return false
        return n >= 9
    }

    /** True on Android's runtime (ART/Dalvik), where ecj cannot read the platform library off the VM. */
    private val isAndroidRuntime: Boolean =
        System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true) ||
            System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)

    fun compile(
        sources: List<Path>,
        classpath: List<Path>,
        outputDir: Path,
        sourceLevel: String = "17",
        bootClasspath: List<Path> = emptyList(),
    ): Result {
        Files.createDirectories(outputDir)

        if (sources.isEmpty()) return Result(true, emptyList())

        // Explicit boot library if given; otherwise the compile classpath on ART (carries android.jar),
        // since the running VM exposes no platform classes ecj can read. Empty on desktop → host JDK.
        val boot = bootClasspath.ifEmpty { if (isAndroidRuntime) classpath else emptyList() }

        // When a boot library is the platform (android.jar, the ART path) at compliance >= 9, the batch
        // front-end is unusable: it would put android.jar on `-bootclasspath`, which ecj rejects at >= 9, and a
        // non-modular jar isn't a valid `--system` (and ART has no jimage). Compile instead via the internal ecj
        // compiler over a CLASSIC (non-module-aware) name environment, which keeps ecj in non-modular mode at any
        // compliance and resolves `java.*` straight from android.jar's bytes — no JRT image, no level cap. The
        // desugar stubs (`StringConcatFactory`, …) must be on [bootClasspath]/[classpath] for Java 9+ concat;
        // D8 desugars the resulting invokedynamic. See [ImageFreeJavaCompiler].
        if (boot.isNotEmpty() && complianceAtLeast9(sourceLevel)) {
            return ImageFreeJavaCompiler.compile(sources, boot + classpath, outputDir, sourceLevel)
        }

        val args = ArrayList<String>()
        args += listOf("-source", sourceLevel, "-target", sourceLevel, "-proc:none", "-nowarn", "-g")
        args += listOf("-d", outputDir.toString())
        if (boot.isNotEmpty()) {
            args += "-bootclasspath"
            args += boot.joinToString(File.pathSeparator) { it.toString() }
        }
        if (classpath.isNotEmpty()) {
            args += "-classpath"
            args += classpath.joinToString(File.pathSeparator) { it.toString() }
        }
        sources.forEach { args += it.toString() }

        val out = StringWriter()
        val err = StringWriter()
        // Take the problems from ecj's compiler requestor, not from the report it prints. The printed form is
        // a locale-dependent rendering that drops the column and the problem id; the CategorizedProblem behind
        // it has both. [parseEcjDiagnostics] stays as the fallback for when the front-end fails before any
        // unit is compiled (a bad argument, a missing source), which produces text and no problems at all.
        val main = RecordingMain(PrintWriter(out), PrintWriter(err))
        val ok = runCatching { main.compile(args.toTypedArray()) }.getOrDefault(false)
        val raw = err.toString() + "\n" + out.toString()
        val messages = raw.lines().filter { it.isNotBlank() }
        val diagnostics = main.problems.ifEmpty { parseEcjDiagnostics(raw) }
        return Result(ok, messages, diagnostics)
    }

    /**
     * The batch front-end, wired to hand us every [CompilationResult] on its way past. Overriding
     * [getBatchRequestor] is ecj's own extension point for this — the delegate still does the real work
     * (writing class files, printing the report), we only observe the problems it carries.
     */
    private class RecordingMain(out: PrintWriter, err: PrintWriter) : Main(out, err, false) {
        val problems = ArrayList<Diagnostic>()

        override fun getBatchRequestor(): ICompilerRequestor {
            val delegate = super.getBatchRequestor()
            return ICompilerRequestor { result ->
                result?.allProblems?.forEach { p -> if (p != null) problems.add(diagnosticOf(p, result)) }
                delegate.acceptResult(result)
            }
        }
    }
}
