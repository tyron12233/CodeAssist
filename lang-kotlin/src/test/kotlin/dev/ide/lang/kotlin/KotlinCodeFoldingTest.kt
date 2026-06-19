package dev.ide.lang.kotlin

import dev.ide.lang.folding.FoldKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin code folding: imports collapse to `import ...` (and by default), function/class bodies fold to
 * `{...}` (the region spans only the text between the braces), block comments fold. Single-line blocks
 * are not foldable.
 */
class KotlinCodeFoldingTest {

    private data class Fold(val text: String, val placeholder: String, val kind: String, val byDefault: Boolean)

    private fun folds(file: String, code: String): List<Fold> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(file)))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.folding!!.folds(doc.file) }
            .map { Fold(code.substring(it.range.start, it.range.end), it.placeholder, it.kind.id, it.collapsedByDefault) }
    }

    @Test
    fun importGroupFoldsCollapsedByDefault() {
        val code = "package demo\n\nimport a.B\nimport c.D\nimport e.F\n\nclass X\n"
        val fold = folds("Imp.kt", code).first { it.kind == FoldKind.IMPORTS.id }
        assertEquals("import ...", fold.placeholder)
        assertTrue(fold.byDefault, "imports collapse by default")
        assertTrue(fold.text.startsWith("import a.B") && fold.text.endsWith("import e.F"), "spans the whole group; got '${fold.text}'")
    }

    @Test
    fun functionBodyFoldsBetweenBraces() {
        val code = "fun test() {\n  val x = 1\n  println(x)\n}\n"
        val fold = folds("Fn.kt", code).first { it.kind == FoldKind.FUNCTION_BODY.id }
        assertEquals("...", fold.placeholder)
        assertTrue(!fold.byDefault, "code blocks open by default")
        // Region is strictly inside the braces: starts after `{`, ends before `}`.
        assertTrue(fold.text.startsWith("\n") && !fold.text.contains("fun test"), "between braces; got '${fold.text}'")
        assertTrue(fold.text.trimEnd().endsWith("println(x)"), "ends before the closing brace; got '${fold.text}'")
    }

    @Test
    fun classBodyFolds() {
        val code = "class Foo {\n  fun a() {}\n  fun b() {}\n}\n"
        assertTrue(folds("Cls.kt", code).any { it.kind == FoldKind.CLASS_BODY.id }, "class body should fold")
    }

    @Test
    fun blockCommentFolds() {
        val code = "/*\n multi\n line\n */\nfun f() = 1\n"
        val fold = folds("Cmt.kt", code).first { it.kind == FoldKind.COMMENT.id }
        assertEquals("/*...*/", fold.placeholder)
    }

    @Test
    fun singleLineBlockDoesNotFold() {
        val code = "fun f() { return }\n"
        assertTrue(folds("One.kt", code).none { it.kind == FoldKind.FUNCTION_BODY.id }, "a one-line body is not foldable")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
