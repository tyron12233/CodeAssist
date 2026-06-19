package dev.ide.lang.jdt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Signature help (parameter info) over the real JDT bindings + attached sources. */
class JdtSignatureHelpTest {

    @Test
    fun showsParametersAndActiveIndexForSameFileMethod() {
        val (an, dir) = workspaceWith("app/Main.java" to "package app; public class Main {}")
        val file = dir.resolve("app/Main.java")
        val code = "package app;\npublic class Main {\n" +
            "  void greet(String name, int count) {}\n" +
            "  void m() {\n    greet(\"x\", |CARET|);\n  }\n}\n"
        val help = signatureHelpAt(an, file, code)
        assertNotNull(help, "expected signature help inside greet(...)")
        val active = help.signatures[help.activeSignature]
        assertTrue(active.label.contains("greet("), "label should show the method; got ${active.label}")
        assertTrue(active.label.contains("name") && active.label.contains("count"),
            "should carry real parameter names; got ${active.label}")
        assertEquals(2, active.parameters.size)
        assertEquals(1, help.activeParameter, "caret after the first comma → second argument")
        // The active parameter's range points into the label and selects the right text.
        val p = active.parameters[1]
        assertEquals("count", active.label.substring(p.labelStart, p.labelEnd).substringAfterLast(' '))
    }

    @Test
    fun resolvesParameterNamesFromAttachedSourcesCrossFile() {
        val (an, dir) = workspaceWith(
            "app/Util.java" to "package app;\npublic class Util {\n  public static void log(String message, int level) {}\n}\n",
            "app/Main.java" to "package app; public class Main {}",
        )
        val file = dir.resolve("app/Main.java")
        val code = "package app;\npublic class Main {\n  void m() {\n    Util.log(|CARET|);\n  }\n}\n"
        val help = signatureHelpAt(an, file, code)
        assertNotNull(help)
        val active = help.signatures[help.activeSignature]
        assertTrue(active.label.contains("message") && active.label.contains("level"),
            "cross-file param names should come from source; got ${active.label}")
        assertEquals(0, help.activeParameter)
    }

    @Test
    fun listsOverloads() {
        val (an, dir) = workspaceWith("app/Main.java" to "package app; public class Main {}")
        val file = dir.resolve("app/Main.java")
        val code = "package app;\npublic class Main {\n" +
            "  void f(int a) {}\n" +
            "  void f(int a, int b) {}\n" +
            "  void m() {\n    f(|CARET|);\n  }\n}\n"
        val help = signatureHelpAt(an, file, code)
        assertNotNull(help)
        assertTrue(help.signatures.size >= 2, "both f overloads should be listed; got ${help.signatures.map { it.label }}")
    }

    @Test
    fun nullOutsideAnyCall() {
        val (an, dir) = workspaceWith("app/Main.java" to "package app; public class Main {}")
        val file = dir.resolve("app/Main.java")
        val code = "package app;\npublic class Main {\n  int x = |CARET|;\n}\n"
        assertEquals(null, signatureHelpAt(an, file, code))
    }
}
