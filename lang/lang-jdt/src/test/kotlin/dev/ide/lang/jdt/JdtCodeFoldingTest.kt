package dev.ide.lang.jdt

import dev.ide.lang.folding.FoldKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Java code folding over the JDT AST: imports, type/method bodies, block/Javadoc comments. */
class JdtCodeFoldingTest {

    private data class Fold(val text: String, val placeholder: String, val kind: String, val byDefault: Boolean)

    private fun folds(code: String): List<Fold> {
        val an = analyzer(emptyList())
        val file = StubFile("/x/app/Main.java", code)
        return runSync { an.folding!!.folds(file) }
            .map { Fold(code.substring(it.range.start, it.range.end), it.placeholder, it.kind.id, it.collapsedByDefault) }
    }

    @Test
    fun importGroupFoldsCollapsedByDefault() {
        val code = """
            package app;
            import java.util.List;
            import java.util.Map;
            import java.io.File;
            public class Main {}
        """.trimIndent()
        val fold = folds(code).first { it.kind == FoldKind.IMPORTS.id }
        assertEquals("import ...", fold.placeholder)
        assertTrue(fold.byDefault, "imports collapse by default")
        assertTrue(fold.text.startsWith("import java.util.List") && fold.text.endsWith("import java.io.File;"), "spans the group; got '${fold.text}'")
    }

    @Test
    fun methodAndTypeBodiesFold() {
        val code = """
            package app;
            public class Main {
              void run() {
                int x = 1;
                System.out.println(x);
              }
            }
        """.trimIndent()
        val all = folds(code)
        assertTrue(all.any { it.kind == FoldKind.CLASS_BODY.id }, "class body folds; got $all")
        val body = all.first { it.kind == FoldKind.BLOCK.id }
        assertEquals("...", body.placeholder)
        assertTrue(!body.text.contains("void run"), "method body is between braces; got '${body.text}'")
    }

    @Test
    fun javadocFolds() {
        val code = """
            package app;
            /**
             * Hello.
             */
            public class Main {}
        """.trimIndent()
        val fold = folds(code).first { it.kind == FoldKind.COMMENT.id }
        assertEquals("/**...*/", fold.placeholder)
    }

    @Test
    fun singleLineBodyDoesNotFold() {
        val code = "package app; public class M { void f() { return; } }"
        assertTrue(folds(code).none { it.kind == FoldKind.BLOCK.id }, "a one-line body is not foldable")
    }
}
