package dev.ide.interp.conformance

import dev.ide.interp.InterpreterException
import dev.ide.interp.lowerProgramFull
import dev.ide.interp.Interpreter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList as streamToList

/**
 * A Kotlin-language **conformance scorecard** for the tree-walking interpreter.
 *
 * Each corpus file under `src/test/resources/conformance/<category>/<name>.kt` follows Kotlin's own
 * `codegen/box` convention: a self-contained program with `fun box(): String` that returns `"OK"` iff the
 * language feature behaves correctly. (They are plain Kotlin — every one of them compiles + returns `"OK"`
 * under a real `kotlinc`, so the corpus measures how close the interpreter is to the real language.)
 *
 * The harness lowers + interprets each `box()` and classifies the result. There is no "expected" column:
 * in Kotlin every `box()` returns `"OK"`, so the only conformant outcome is [Outcome.PASS]; everything else
 * is the distance left to cover.
 */
object ConformanceHarness {

    /** The four ways a `box()` can land, ordered worst-news-first for the report. */
    enum class Verdict { PASS, FAIL, ERROR, GAP }

    data class Outcome(
        val category: String,
        val name: String,
        val verdict: Verdict,
        /** Human note: the wrong value (FAIL), the boundary reason (GAP), or the throwable (ERROR). */
        val detail: String,
    )

    data class CategoryScore(val category: String, val outcomes: List<Outcome>) {
        val pass get() = outcomes.count { it.verdict == Verdict.PASS }
        val fail get() = outcomes.count { it.verdict == Verdict.FAIL }
        val error get() = outcomes.count { it.verdict == Verdict.ERROR }
        val gap get() = outcomes.count { it.verdict == Verdict.GAP }
        val total get() = outcomes.size
        val passPct get() = if (total == 0) 0 else (pass * 100) / total
    }

    data class Report(val categories: List<CategoryScore>) {
        val all get() = categories.flatMap { it.outcomes }
        val pass get() = all.count { it.verdict == Verdict.PASS }
        val fail get() = all.count { it.verdict == Verdict.FAIL }
        val error get() = all.count { it.verdict == Verdict.ERROR }
        val gap get() = all.count { it.verdict == Verdict.GAP }
        val total get() = all.size
        val passPct get() = if (total == 0) 0 else (pass * 100) / total
    }

    /** Run the whole corpus and produce a [Report]. */
    fun run(): Report {
        val root = corpusRoot()
        val files = Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.streamToList()
        }.sorted()
        val byCategory = files.groupBy { root.relativize(it).let { rel -> if (rel.nameCount > 1) rel.getName(0).toString() else "(root)" } }
        val scores = byCategory.toSortedMap().map { (category, catFiles) ->
            CategoryScore(category, catFiles.sorted().map { evaluate(category, it) })
        }
        return Report(scores)
    }

    private fun evaluate(category: String, file: Path): Outcome {
        val name = file.fileName.toString().removeSuffix(".kt")
        val code = Files.readString(file)
        return try {
            val (functions, classes) = lowerProgramFull(code)
            val box = functions["box/0"]
                ?: return Outcome(category, name, Verdict.ERROR, "no `fun box(): String` (have ${functions.keys})")
            if (!box.isComplete) {
                // The box itself couldn't be fully lowered — report the precise frontier reasons.
                val reasons = box.diagnostics.joinToString("; ") { it.reason }
                return Outcome(category, name, Verdict.GAP, reasons.ifBlank { "incomplete lowering" })
            }
            when (val result = Interpreter(functions, classes = classes).call(box, emptyList())) {
                "OK" -> Outcome(category, name, Verdict.PASS, "")
                else -> Outcome(category, name, Verdict.FAIL, "got ${render(result)}, expected \"OK\"")
            }
        } catch (e: InterpreterException) {
            // The interpreter's honest "I won't guess" boundary — a known gap, not a crash.
            Outcome(category, name, Verdict.GAP, e.message ?: "InterpreterException")
        } catch (e: Throwable) {
            // Anything else (NPE, reflection failure, parse blow-up) is an unexpected robustness problem.
            Outcome(category, name, Verdict.ERROR, "${e::class.simpleName}: ${e.message}")
        }
    }

    private fun render(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        else -> "$v (${v::class.simpleName})"
    }

    private fun corpusRoot(): Path {
        ConformanceHarness::class.java.classLoader.getResource("conformance")?.let { url ->
            if (url.protocol == "file") return Paths.get(url.toURI())
        }
        for (candidate in listOf("src/test/resources/conformance", "interp-core/src/test/resources/conformance")) {
            val p = Paths.get(candidate)
            if (Files.isDirectory(p)) return p
        }
        error("conformance corpus directory not found on classpath or in the source tree")
    }

    /** Render a fixed-width scorecard + a worst-news-first detail listing. */
    fun format(report: Report): String = buildString {
        val nameW = (report.categories.maxOfOrNull { it.category.length } ?: 10).coerceAtLeast(10)
        appendLine("=".repeat(nameW + 44))
        appendLine("  INTERPRETER ↔ KOTLIN CONFORMANCE SCORECARD")
        appendLine("=".repeat(nameW + 44))
        appendLine("%-${nameW}s  %5s %5s %5s %5s  %6s  %5s".format("category", "PASS", "GAP", "FAIL", "ERR", "total", "pass%"))
        appendLine("-".repeat(nameW + 44))
        for (c in report.categories) {
            appendLine("%-${nameW}s  %5d %5d %5d %5d  %6d  %4d%%".format(c.category, c.pass, c.gap, c.fail, c.error, c.total, c.passPct))
        }
        appendLine("-".repeat(nameW + 44))
        appendLine("%-${nameW}s  %5d %5d %5d %5d  %6d  %4d%%".format("TOTAL", report.pass, report.gap, report.fail, report.error, report.total, report.passPct))
        appendLine()

        fun section(title: String, verdict: Verdict) {
            val items = report.all.filter { it.verdict == verdict }
            if (items.isEmpty()) return
            appendLine(title)
            for (o in items) appendLine("  ${o.category}/${o.name}  →  ${o.detail}")
            appendLine()
        }
        section("CORRECTNESS BUGS (ran, wrong answer — investigate):", Verdict.FAIL)
        section("RUNTIME ERRORS (unexpected throw — robustness):", Verdict.ERROR)
        section("FRONTIER (unsupported — the gap to Kotlin):", Verdict.GAP)
    }
}
