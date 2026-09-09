package dev.ide.lang.jdt

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.dom.TextRange
import dev.ide.lang.hints.InlayHintKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Postfix completion templates + inlay hints over the real JDT, on the running JDK's boot classpath. */
class PostfixAndInlayTest {

    private fun ws() = workspaceWith("app/Main.java" to "package app; public class Main {}")

    @Test
    fun postfixOffersTemplatesAfterDot() {
        val (an, dir) = ws()
        val file = dir.resolve("app/Main.java")
        val code = "package app;\npublic class Main {\n  void m() {\n    String s = \"\";\n    s.|CARET|\n  }\n}\n"
        val res = completeResult(an, file, code)
        val labels = res.items.map { it.label }
        assertTrue("sout" in labels, "expected sout postfix; got $labels")
        assertTrue("var" in labels, "expected var postfix; got $labels")
        assertTrue("nn" in labels, "expected nn postfix; got $labels")
        // String isn't a boolean, so `not`/`if` must not be offered.
        assertTrue("not" !in labels, "not should not apply to a String")

        val sout = res.items.first { it.label == "sout" }
        assertEquals(CompletionItemKind.SNIPPET, sout.kind)
        assertEquals("System.out.println(s);", sout.insertText)
        assertTrue(sout.caret is CaretAction.ExpandSnippet, "sout should carry a snippet expansion")
        // The `receiver.` text is removed via an additional edit (the main range only covers the key).
        assertTrue(sout.additionalEdits.any { it.newText.isEmpty() }, "expected a delete-receiver edit")
    }

    @Test
    fun forPostfixHasLinkedLoopVariable() {
        val (an, dir) = ws()
        val file = dir.resolve("app/Main.java")
        val code = "package app;\npublic class Main {\n  void m(int[] arr) {\n    arr.|CARET|\n  }\n}\n"
        val res = completeResult(an, file, code)
        val forItem = res.items.firstOrNull { it.label == "for" }
        assertNotNull(forItem, "expected a for postfix on an array")
        assertTrue(forItem.insertText.contains("for (int i = 0; i < arr.length; i++)"), forItem.insertText)
        val snippet = (forItem.caret as CaretAction.ExpandSnippet).expansion
        // The loop variable `i` appears three times — one linked stop with three ranges (multiple cursors).
        val stop = snippet.stops.first { it.index == 1 }
        assertEquals(3, stop.ranges.size, "the loop var should be a linked stop with 3 occurrences")
    }

    @Test
    fun liveTemplateSoutWithoutReceiver() {
        val (an, dir) = ws()
        val file = dir.resolve("app/Main.java")
        val code = "package app;\npublic class Main {\n  void m() {\n    sou|CARET|\n  }\n}\n"
        val res = completeResult(an, file, code)
        val sout = res.items.firstOrNull { it.label == "sout" }
        assertNotNull(sout, "expected a `sout` live template at a statement position; got ${res.items.map { it.label }}")
        assertEquals(CompletionItemKind.SNIPPET, sout.kind)
        assertEquals("System.out.println();", sout.insertText)
        assertTrue(sout.caret is CaretAction.ExpandSnippet)
    }

    @Test
    fun crossFileParameterNamesFromSources() {
        val (an, dir) = workspaceWith(
            "app/Util.java" to "package app;\npublic class Util {\n  public static void log(String message, int level) {}\n}\n",
            "app/Main.java" to "package app; public class Main {}",
        )
        val file = dir.resolve("app/Main.java")
        val code = "package app;\npublic class Main {\n  void m() {\n    Util.log(\"hi\", 2);\n  }\n}\n"
        val hints = runSync { an.inlayHints.hints(StubFile(file.toString(), code), TextRange(0, code.length)) }
        val params = hints.filter { it.kind == InlayHintKind.PARAMETER }.map { it.parts.joinToString("") { p -> p.text } }
        assertTrue(params.any { it.startsWith("message") }, "expected a cross-file `message:` hint from sources; got $params")
        assertTrue(params.any { it.startsWith("level") }, "expected a cross-file `level:` hint from sources; got $params")
    }

    @Test
    fun completionShowsSourceParameterNamesAndDocs() {
        val (an, dir) = workspaceWith(
            "app/Util.java" to "package app;\npublic class Util {\n  /** Logs a message at a level. */\n  public static void log(String message, int level) {}\n}\n",
            "app/Main.java" to "package app; public class Main {}",
        )
        val file = dir.resolve("app/Main.java")
        val code = "package app;\npublic class Main {\n  void m() {\n    Util.lo|CARET|\n  }\n}\n"
        val res = completeResult(an, file, code)
        val log = res.items.firstOrNull { it.label.startsWith("log(") }
        assertNotNull(log, "expected a `log` member; got ${res.items.map { it.label }}")
        assertTrue(log.label.contains("message") && log.label.contains("level"),
            "completion should show real parameter names from source; got ${log.label}")
        assertTrue(log.documentation?.contains("Logs a message") == true,
            "completion should carry javadoc from source; got ${log.documentation}")
    }

    @Test
    fun inlayHintsForVarAndParameterNames() {
        val (an, dir) = ws()
        val file = dir.resolve("app/Main.java")
        val code = buildString {
            append("package app;\n")
            append("public class Main {\n")
            append("  void greet(String name, int count) {}\n")
            append("  void m() {\n")
            append("    var s = \"hi\";\n")
            append("    greet(\"x\", 3);\n")
            append("  }\n")
            append("}\n")
        }
        val hints = runSync { an.inlayHints.hints(StubFile(file.toString(), code), TextRange(0, code.length)) }

        val typeHint = hints.firstOrNull { it.kind == InlayHintKind.TYPE }
        assertNotNull(typeHint, "expected a var type hint; got $hints")
        assertTrue(typeHint.parts.joinToString("") { it.text }.contains("String"), "var s should be inferred String")

        val paramHints = hints.filter { it.kind == InlayHintKind.PARAMETER }.map { it.parts.joinToString("") { p -> p.text } }
        assertTrue(paramHints.any { it.startsWith("count") }, "expected a `count:` parameter hint; got $paramHints")
    }
}
