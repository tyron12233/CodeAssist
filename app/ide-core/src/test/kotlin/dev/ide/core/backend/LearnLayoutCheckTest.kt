package dev.ide.core.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearnLayoutCheckTest {

    private val check = LayoutCheck(
        views = listOf(
            ViewRule("LinearLayout", "a vertical LinearLayout", attrs = mapOf("android:orientation" to "vertical")),
            ViewRule("EditText", "two EditText fields", parent = "LinearLayout", count = 2),
            ViewRule("Button", "a Button with id signIn", id = "signIn"),
        ),
    )

    private val good = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="vertical">
            <EditText android:layout_width="match_parent" android:layout_height="wrap_content" />
            <EditText android:layout_width="match_parent" android:layout_height="wrap_content" />
            <android.widget.Button android:id="@+id/signIn"
                android:layout_width="wrap_content" android:layout_height="wrap_content" />
        </LinearLayout>
    """.trimIndent()

    @Test
    fun aLayoutWithEveryRuleAndACleanRenderPasses() {
        val r = checkLayout(good, check) { emptyList() }
        assertTrue(r.passed, r.message + "\n" + r.output)
        assertEquals(4, r.output.lines().count { it.startsWith("✓") })
    }

    @Test
    fun missingPiecesAreAllListed() {
        val xml = good.replace("android:orientation=\"vertical\"", "").replace("@+id/signIn", "@+id/go")
        val r = checkLayout(xml, check) { emptyList() }
        assertFalse(r.passed)
        assertTrue(r.compiled)
        assertTrue("a vertical LinearLayout" in r.message && "a Button with id signIn" in r.message, r.message)
    }

    @Test
    fun malformedXmlIsReportedAsNotCompiling() {
        val r = checkLayout("<LinearLayout><Button></LinearLayout>", check) { emptyList() }
        assertFalse(r.passed)
        assertFalse(r.compiled)
    }

    @Test
    fun aRenderProblemFailsAnOtherwiseCorrectLayout() {
        val r = checkLayout(good, check) { listOf("Foo: unknown tag") }
        assertFalse(r.passed)
        assertEquals(listOf("Foo: unknown tag"), r.diagnostics)
        val noRender = checkLayout(good, check.copy(cleanRender = false)) { null }
        assertTrue(noRender.passed)
    }
}
