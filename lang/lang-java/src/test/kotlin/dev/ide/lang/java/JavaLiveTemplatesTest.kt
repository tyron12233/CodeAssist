package dev.ide.lang.java

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.java.completion.JavaLiveTemplates
import dev.ide.lang.java.completion.JavaPlace
import dev.ide.lang.java.completion.JavaTemplateContexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Java live templates appear only where they fit (`psvm` among members, `sout` at a statement, nothing in a
 * comment or after a `.`), the Android ones only in an Android module, and the ones that read their
 * surroundings fill in the class, method and parameters.
 */
class JavaLiveTemplatesTest {

    /** [code] with `|` at the caret: the token before it is the prefix. */
    private fun templates(code: String, android: Boolean = false): List<CompletionItem> {
        val caret = code.indexOf('|')
        val text = code.removeRange(caret, caret + 1)
        var start = caret
        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
        // The backend's planner adds an import the file lacks; this stand-in does the same by text.
        val plan = { fqn: String -> if ("import $fqn;" in text) null else TextEdit(TextRange(0, 0), "import $fqn;\n") }
        return JavaLiveTemplates.itemsFor(text, start, text.substring(start, caret), android, plan)
    }

    private fun place(code: String): JavaPlace {
        val caret = code.indexOf('|')
        return JavaTemplateContexts.at(code.removeRange(caret, caret + 1), caret).place
    }

    private fun labels(code: String, android: Boolean = false) = templates(code, android).map { it.label }.toSet()

    @Test
    fun placesAreReadOffTheText() {
        val cases = mapOf(
            "class A {\n    |\n}" to JavaPlace.CLASS_BODY,
            "class A {\n    @Override\n    |\n}" to JavaPlace.CLASS_BODY,
            "class A {\n    int x = |\n}" to JavaPlace.EXPRESSION,
            "class A {\n    void m() {\n        |\n    }\n}" to JavaPlace.STATEMENT,
            "class A {\n    void m() {\n        int x = 1;\n        |\n    }\n}" to JavaPlace.STATEMENT,
            "class A {\n    void m() {\n        if (x)\n            |\n    }\n}" to JavaPlace.STATEMENT,
            "class A {\n    void m() {\n        foo(|)\n    }\n}" to JavaPlace.EXPRESSION,
            "class A {\n    void m() {\n        return |\n    }\n}" to JavaPlace.EXPRESSION,
            "class A {\n    void m() {\n        list.|\n    }\n}" to JavaPlace.NONE,
            "class A {\n    void m() {\n        // so|\n    }\n}" to JavaPlace.NONE,
            "class A {\n    void m() {\n        String s = \"so|\n    }\n}" to JavaPlace.NONE,
            "class A {\n    int[] a = {\n        |\n    };\n}" to JavaPlace.EXPRESSION,
            "class A {\n    void m() {\n        r = new Runnable() {\n            |\n        };\n    }\n}" to JavaPlace.CLASS_BODY,
            "class A {\n    void m() {\n        switch (x) {\n            case 1:\n                |\n        }\n    }\n}" to JavaPlace.STATEMENT,
            "package a;\n|" to JavaPlace.NONE,
        )
        val wrong = cases.filter { (code, want) -> place(code) != want }.map { (code, want) -> "$want ≠ ${place(code)} in:\n$code" }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n\n"))
    }

    @Test
    fun eachTemplateOnlyWhereItFits() {
        assertTrue("psvm" in labels("class A {\n    ps|\n}"))
        assertTrue("psvm" !in labels("class A {\n    void m() {\n        ps|\n    }\n}"), "no main method inside a method")
        assertTrue("sout" in labels("class A {\n    void m() {\n        sou|\n    }\n}"))
        assertTrue("sout" !in labels("class A {\n    sou|\n}"), "no statement among the members")
        assertTrue("sout" !in labels("class A {\n    void m() {\n        foo(sou|)\n    }\n}"), "no statement in an argument")
        assertTrue("fori" !in labels("class A {\n    void m() {\n        // for|\n    }\n}"), "nothing in a comment")
        assertTrue("St" in labels("class A {\n    void m() {\n        foo(S|)\n    }\n}"), "St fits an expression")
        assertTrue("toar" in labels("class A {\n    void m() {\n        Object[] a = to|\n    }\n}"))
    }

    @Test
    fun androidTemplatesOnlyInAnAndroidModule() {
        val code = "class A {\n    void onCreate(Bundle state) {\n        log|\n    }\n}"
        assertTrue(labels(code).none { it.startsWith("log") }, "no Log templates outside Android")
        assertEquals(setOf("logd", "logi", "logw", "loge", "logm"), labels(code, android = true))
    }

    @Test
    fun templatesReadTheEnclosingClassAndMethod() {
        val method = "class MainActivity {\n    void onCreate(Bundle state, int flags) {\n        |\n    }\n}"
        fun item(key: String, code: String = method.replace("|", "$key|")) =
            assertNotNull(templates(code, android = true).firstOrNull { it.label == key }, "no $key")

        assertEquals("Log.d(TAG, \"onCreate: \");", item("logd").insertText)
        assertEquals(
            "Log.d(TAG, \"onCreate() called with: state = [\" + state + \"]\" + \", flags = [\" + flags + \"]\");",
            item("logm").insertText,
        )
        assertEquals("System.out.println(\"MainActivity.onCreate\");", item("soutm").insertText)
        assertEquals("System.out.println(\"state = \" + state + \", flags = \" + flags);", item("soutp").insertText)
        assertEquals("private static final String TAG = \"MainActivity\";", item("logt", "class MainActivity {\n    logt|\n}").insertText)
        assertEquals("Toast.makeText(MainActivity.this, \"\", Toast.LENGTH_SHORT).show();", item("Toast").insertText)
    }

    @Test
    fun androidTemplatesImportWhatTheyUse() {
        val code = "package app;\n\nimport android.os.Bundle;\n\nclass A {\n    void m() {\n        logd|\n    }\n}"
        val edit = templates(code, android = true).first { it.label == "logd" }.additionalEdits.single()
        assertEquals("import android.util.Log;\n", edit.newText)
        val already = "package app;\n\nimport android.util.Log;\n\nclass A {\n    void m() {\n        logd|\n    }\n}"
        assertTrue(templates(already, android = true).first { it.label == "logd" }.additionalEdits.isEmpty())
    }

    @Test
    fun aMultiLineTemplateTakesOnTheLinesIndent() {
        val fori = templates("class A {\n    void m() {\n        fori|\n    }\n}").first { it.label == "fori" }
        assertEquals("for (int i = 0; i < n; i++) {\n            \n        }", fori.insertText)
    }
}
