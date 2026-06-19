package dev.ide.lang.jdt

import dev.ide.lang.highlight.HighlightModifier
import kotlin.test.Test
import kotlin.test.assertTrue

/** Type-aware Java coloring over JDT bindings: fields vs locals vs parameters, types, methods, static/final. */
class JdtSemanticHighlightTest {

    private data class Tok(val text: String, val kind: String, val mods: Set<HighlightModifier>)

    private fun tokens(code: String): List<Tok> {
        val an = analyzer(emptyList())
        val file = StubFile("/x/app/Main.java", code)
        return runSync { an.semanticHighlighter!!.highlight(file) }
            .map { Tok(code.substring(it.range.start, it.range.end), it.kind.id, it.modifiers) }
    }

    @Test
    fun classifiesFieldsLocalsParamsAndMethods() {
        val code = """
            package app;
            public class Main {
              static final int COUNT = 3;
              int field = 1;
              void run(int p) {
                int local = p + field;
                System.out.println(local);
              }
            }
        """.trimIndent()
        val toks = tokens(code)

        assertTrue(toks.any { it.text == "field" && it.kind == "field" }, "field should be a field; got $toks")
        assertTrue(toks.any { it.text == "p" && it.kind == "parameter" }, "p should be a parameter; got $toks")
        assertTrue(toks.any { it.text == "local" && it.kind == "localVariable" }, "local should be a local; got $toks")
        assertTrue(toks.any { it.text == "println" && it.kind == "method" }, "println should be a method; got $toks")
        assertTrue(toks.any { it.text == "System" && it.kind == "class" }, "System should be a class; got $toks")
        // COUNT is static + final.
        assertTrue(
            toks.any { it.text == "COUNT" && it.kind == "field" && HighlightModifier.STATIC in it.mods && HighlightModifier.READONLY in it.mods },
            "COUNT should be a static final field; got $toks",
        )
    }
}
