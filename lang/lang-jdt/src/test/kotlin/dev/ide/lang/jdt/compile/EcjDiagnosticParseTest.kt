package dev.ide.lang.jdt.compile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ecj textual report must parse into structured diagnostics that carry the *actual* description (not just
 * the `N. ERROR in … (at line N)` header), so the layout-preview pane can show a useful, short, copyable error.
 */
class EcjDiagnosticParseTest {

    @Test fun `parses file, line, and the description message`() {
        val report = """
            ----------
            1. ERROR in /storage/emulated/0/Android/data/com.x/files/projects/quran-app/app/src/main/kotlin/dev/mutwakil/quranapp/activities/MainActivity.java (at line 1)
            	package dev.mutwakil.quranapp.activities;
            	^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            The declared package "dev.mutwakil.quranapp.activities" does not match the expected package "quranapp.activities"
            ----------
            1 problem (1 error)
        """.trimIndent()

        val diags = JdtBatchCompiler.parseEcjDiagnostics(report)
        assertEquals(1, diags.size)
        val d = diags.single()
        assertTrue(d.isError)
        assertEquals(1, d.line)
        assertTrue(d.file!!.endsWith("MainActivity.java"), "full path retained on the diagnostic: ${d.file}")
        assertEquals("The declared package \"dev.mutwakil.quranapp.activities\" does not match the expected package \"quranapp.activities\"", d.message)
        // Crucially, the description is NOT the header/summary noise the old filter kept.
        assertTrue("ERROR in" !in d.message && "1 problem" !in d.message)
    }

    @Test fun `parses multiple problems and separates errors from warnings`() {
        val report = """
            ----------
            1. ERROR in /p/Foo.java (at line 12)
            	int x = bar();
            	        ^^^
            The method bar() is undefined for the type Foo
            ----------
            2. WARNING in /p/Foo.java (at line 3)
            	import java.util.List;
            	       ^^^^^^^^^^^^^^
            The import java.util.List is never used
            ----------
            2 problems (1 error, 1 warning)
        """.trimIndent()

        val diags = JdtBatchCompiler.parseEcjDiagnostics(report)
        assertEquals(2, diags.size)
        assertEquals(1, diags.count { it.isError })
        assertEquals("The method bar() is undefined for the type Foo", diags.first { it.isError }.message)
        assertEquals(12, diags.first { it.isError }.line)
    }

    @Test fun `no diagnostics for clean output`() {
        assertTrue(JdtBatchCompiler.parseEcjDiagnostics("").isEmpty())
        assertTrue(JdtBatchCompiler.parseEcjDiagnostics("just some unrelated text\nover two lines").isEmpty())
    }
}
