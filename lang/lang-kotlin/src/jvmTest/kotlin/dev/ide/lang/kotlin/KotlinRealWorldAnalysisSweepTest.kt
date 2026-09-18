package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ANALYSIS, swept over a real Kotlin checkout — the other half of the parser sweep in `:kotlin-syntax`.
 *
 * Agreeing with the compiler about the TREE says nothing about what the editor then claims is wrong with it.
 * This runs the semantic checks over code that is known-good by construction (the Kotlin standard library's
 * own sources, which the compiler builds every day) and treats every ERROR as a suspected FALSE POSITIVE.
 * That is the failure this project cares most about: a missing diagnostic is a gap, a wrong one is a bug
 * report from a user whose correct code is underlined.
 *
 * It is not a parity test against the compiler's diagnostics — our checks are deliberately a subset, so
 * counting what we DON'T report would measure scope rather than correctness. It is a false-positive hunt,
 * plus the harder guarantee that nothing in the pipeline throws on real input.
 *
 * Opt-in, like the parser sweep, because it needs a checkout:
 *
 *     ./gradlew :lang-kotlin:jvmTest --tests '*RealWorldAnalysisSweep*' -Dkt.externalCorpus=<kotlin checkout>
 *
 * NOTE on what is and is not withheld: the sweep analyzes the files WITHOUT the stdlib's own classpath, so
 * the unresolved-reference checks gate themselves off (see `IncrementalSemanticAnalysis.resolveReady`). What
 * still runs is every structural and flow check — overrides, modifiers, `when` exhaustiveness, val
 * reassignment, bounds — which is exactly the population that can false-positive without an index.
 */
class KotlinRealWorldAnalysisSweepTest {

    @Test
    fun theCheckersReportNothingOnTheKotlinStandardLibrarysOwnSources() {
        val root = System.getProperty("kt.externalCorpus")?.let(::File) ?: return
        val stdlib = File(root, "libraries/stdlib")
        assertTrue(stdlib.isDirectory, "expected libraries/stdlib under $root")

        val files = stdlib.walkTopDown()
            .onEnter { it.name !in setOf("build", "test", "testData", ".git") && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
        assertTrue(files.size > 500, "expected the stdlib sources, found ${files.size}")

        val byCode = HashMap<String, Int>()
        // Up to three DISTINCT messages per code. One sample names the category; three show whether the hits
        // are one repeated shape (usually a single missing rule) or a scatter of unrelated ones.
        val samples = HashMap<String, MutableSet<String>>()
        // Per code, how the hits are spread over files. A count alone cannot tell a real false positive from a
        // corpus artefact: 3,546 "property must be initialized" all landing in the JS builtins (bodyless
        // `actual` declarations the compiler only accepts with -Xallow-kotlin-package) is one quirk, whereas
        // the same count spread over 400 ordinary files is a bug every user would hit.
        val byCodeFiles = HashMap<String, MutableMap<String, Int>>()
        var crashed = 0
        var withErrors = 0
        for (file in files) {
            val text = runCatching { file.readText().replace("\r\n", "\n") }.getOrNull() ?: continue
            val relative = file.relativeTo(stdlib).path.replace(File.separatorChar, '/')
            // The RELATIVE path, not the base name. stdlib has many same-named files across its platform
            // source sets (`Array.kt`, `Collections.kt`, …); keying on the name alone collapses them onto one
            // VirtualFile, the source model merges their declarations, and the sweep then invents thousands of
            // repeated-modifier and conflicting-declaration reports that the checkers never actually produce.
            val doc = SnippetDoc(text, DiskFile(srcDir.resolve(relative)))
            val diagnostics = runCatching {
                runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            }.getOrElse { crashed++; emptyList() }

            // Syntax errors are the parser's business and the `:kotlin-syntax` sweep already gates them; a
            // stdlib file that fails to PARSE would show up there as a divergence from the compiler.
            val errors = diagnostics.filter {
                it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
            }
            if (errors.isEmpty()) continue
            withErrors++
            for (d in errors) {
                val code = d.code ?: "<none>"
                byCode.merge(code, 1, Int::plus)
                byCodeFiles.getOrPut(code) { HashMap() }.merge(relative, 1, Int::plus)
                samples.getOrPut(code) { LinkedHashSet() }.let { if (it.size < 3) it += "$relative: ${d.message}" }
            }
        }

        println("stdlib sweep: ${files.size - withErrors}/${files.size} files clean, $crashed crashed")
        byCode.entries.sortedByDescending { it.value }.forEach { (code, n) ->
            val spread = byCodeFiles[code].orEmpty()
            println("  $n x $code  (across ${spread.size} files)")
            samples[code].orEmpty().forEach { println("      $it") }
            spread.entries.sortedByDescending { it.value }.take(3)
                .forEach { (f, c) -> println("      $c in $f") }
        }

        // Nothing may THROW: the analysis runs on every keystroke, and an exception is a dead editor pane.
        assertTrue(crashed == 0, "the analysis threw on $crashed of ${files.size} stdlib files")
    }

    companion object {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
