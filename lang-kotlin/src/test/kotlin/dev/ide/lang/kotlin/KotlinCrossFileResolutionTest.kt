package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cross-file resolution of compiler-synthesized members the parse-only source model must reconstruct:
 * a class's implicit no-arg constructor (`class Toolbox { fun t() }` → `Toolbox()` is a constructor call, not
 * a value invocation) and an enum's `values()`/`valueOf()`/`entries`. Declared in separate on-disk files so
 * the symbol model (which walks the source roots) carries them while the analyzed file is a different buffer.
 */
class KotlinCrossFileResolutionTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun crossFileNoArgConstructorNotFlagged() {
        val diags = diagnose("Main.kt", "package demo\nfun main() { val t = Toolbox() }")
        assertTrue(diags.none { it.code == "kt.notCallable" },
            "a constructor call to a no-explicit-ctor class must not be 'not callable'; got $diags")
        assertTrue(diags.none { it.code == "kt.unresolved" && it.message.contains("Toolbox") },
            "Toolbox should resolve cross-file; got $diags")
    }

    @Test
    fun crossFileEmptyClassConstructorNotFlagged() {
        val diags = diagnose("Main.kt", "package demo\nfun main() { val e = Empty() }")
        assertTrue(diags.none { it.code == "kt.notCallable" }, "Empty() must not be 'not callable'; got $diags")
    }

    @Test
    fun crossFileMethodCallResolves() {
        val diags = diagnose("Main.kt", "package demo\nfun main() { Toolbox().test() }")
        assertTrue(diags.none { it.code == "kt.unresolved" },
            "Toolbox().test() should resolve cross-file; got $diags")
    }

    @Test
    fun realValueInvocationStillFlagged() {
        // The notCallable check must still catch a genuine non-callable value invocation.
        val diags = diagnose("Main.kt", "package demo\nfun main() { val x = 5; x() }")
        assertTrue(diags.any { it.code == "kt.notCallable" },
            "invoking an Int value must still be flagged; got $diags")
    }

    @Test
    fun enumSyntheticStaticsResolve() {
        val diags = diagnose(
            "Main.kt",
            "package demo\nfun main() { Color.values(); Color.valueOf(\"RED\"); val e = Color.entries }",
        )
        assertTrue(diags.none { it.code == "kt.unresolved" },
            "enum values()/valueOf()/entries should resolve on a source enum; got $diags")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Toolbox.kt" to "package demo\nclass Toolbox { fun test() {} }",
                "Empty.kt" to "package demo\nclass Empty",
                "Colors.kt" to "package demo\nenum class Color { RED, GREEN }",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
