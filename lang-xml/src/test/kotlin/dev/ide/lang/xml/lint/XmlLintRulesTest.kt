package dev.ide.lang.xml.lint

import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.xml.XmlIncrementalParser
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for the pure XML lint detection (no IDE bootstrap). */
class XmlLintRulesTest {

    private fun parse(xml: String): ParsedFile = XmlIncrementalParser().parseFull(Doc(xml))
    private val isView: (String) -> Boolean = { it in setOf("TextView", "Button", "LinearLayout") || it.contains('.') }

    @Test
    fun flagsMissingNamespace() {
        // android: used but the root has no xmlns:android.
        val hits = XmlLintRules.missingNamespaces(parse("<LinearLayout android:orientation=\"vertical\"></LinearLayout>"))
        assertEquals(listOf("android"), hits.map { it.prefix })
        // ...and not flagged once it's declared.
        assertTrue(XmlLintRules.missingNamespaces(
            parse("<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:orientation=\"vertical\"/>")
        ).isEmpty())
    }

    @Test
    fun flagsMissingAppAndToolsNamespaces() {
        // app: and tools: used, android: declared → only app + tools are reported.
        val hits = XmlLintRules.missingNamespaces(parse(
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"" +
                " app:layout_constraintTop_toTopOf=\"parent\" tools:text=\"x\"/>"
        ))
        assertEquals(setOf("app", "tools"), hits.map { it.prefix }.toSet())
        assertTrue(hits.all { it.uri.startsWith("http://schemas.android.com/") })
    }

    @Test
    fun flagsHardcodedTextButNotResourceRefs() {
        val hits = XmlLintRules.hardcodedText(parse("""<TextView android:text="Hello world" android:hint="@string/h"/>"""))
        assertEquals(1, hits.size)
        assertEquals("android:text", hits[0].attrName)
        assertEquals("Hello world", hits[0].value)
    }

    @Test
    fun flagsMissingSizeOnViewsOnly() {
        val xml = """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <TextView android:layout_width="wrap_content"/>
            <Button/>
        </LinearLayout>""".trimIndent()
        val dims = XmlLintRules.missingSize(parse(xml), isView).map { "${it.tag}:${it.dim}" }
        // TextView is missing only height; Button is missing both. LinearLayout (root) missing both too.
        assertTrue("TextView:layout_height" in dims)
        assertTrue("TextView:layout_width" !in dims)
        assertTrue("Button:layout_width" in dims && "Button:layout_height" in dims)
    }

    @Test
    fun flagsWrongAttributeWhenCheckerSaysNotAllowed() {
        // The checker is the only authority on validity; the rule just locates eligible attributes.
        val checker = XmlAttributeChecker { _, tag, _, attr ->
            if (tag == "TextView" && attr == "android:bogus") AttrInfo.NotAllowed else AttrInfo.Indeterminate
        }
        val xml = """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:bogus="x" android:text="hi"/>"""
        val unknown = XmlLintRules.attributeProblems(parse(xml), "res/layout/a.xml", checker)
            .filterIsInstance<XmlLintRules.AttributeProblem.Unknown>()
        assertEquals(1, unknown.size)
        assertEquals("android:bogus", unknown[0].attribute)
        // The underline is the name; the removal range covers the whole attribute plus its leading space.
        assertEquals("android:bogus", xml.substring(unknown[0].range.start, unknown[0].range.end))
        assertEquals(" android:bogus=\"x\"", xml.substring(unknown[0].removalRange.start, unknown[0].removalRange.end))
    }

    @Test
    fun flagsInvalidEnumValueButNotRefsOrFreeForm() {
        val checker = XmlAttributeChecker { _, _, _, attr ->
            when (attr) {
                "android:visibility" -> AttrInfo.Recognized(setOf("visible", "invisible", "gone"))
                "android:text" -> AttrInfo.Recognized(null) // free-form
                else -> AttrInfo.Indeterminate
            }
        }
        fun invalid(xml: String) = XmlLintRules.attributeProblems(parse(xml), "res/layout/a.xml", checker)
            .filterIsInstance<XmlLintRules.AttributeProblem.InvalidValue>()

        val bad = invalid("""<TextView android:visibility="goen"/>""")
        assertEquals(1, bad.size); assertEquals("goen", bad[0].value)
        assertTrue(invalid("""<TextView android:visibility="gone"/>""").isEmpty(), "a valid enum value isn't flagged")
        assertTrue(invalid("""<TextView android:visibility="@{vm.visible}"/>""").isEmpty(), "a data-binding expr is skipped")
        assertTrue(invalid("""<TextView android:visibility="?attr/foo"/>""").isEmpty(), "a theme attr is skipped")
        assertTrue(invalid("""<TextView android:text="anything goes"/>""").isEmpty(), "a free-form value isn't checked")
    }

    @Test
    fun flagsInvalidFlagTokenButNotAllValidCombinations() {
        val checker = XmlAttributeChecker { _, _, _, attr ->
            if (attr == "android:gravity") AttrInfo.Recognized(setOf("start", "end", "center", "top", "bottom"), isFlag = true)
            else AttrInfo.Indeterminate
        }
        fun invalid(xml: String) = XmlLintRules.attributeProblems(parse(xml), "res/layout/a.xml", checker)
            .filterIsInstance<XmlLintRules.AttributeProblem.InvalidValue>()
        assertEquals(1, invalid("""<TextView android:gravity="center|botom"/>""").size, "a bad token in a flag combo is flagged")
        assertTrue(invalid("""<TextView android:gravity="center|top"/>""").isEmpty(), "an all-valid flag combo isn't flagged")
    }

    @Test
    fun skipsNamespaceDeclarationsToolsAndUnprefixedAttributes() {
        // A checker that would flag everything if the rule let it through.
        val checker = XmlAttributeChecker { _, _, _, _ -> AttrInfo.NotAllowed }
        val xml = """<TextView xmlns:tools="http://schemas.android.com/tools" tools:text="x" style="@style/foo"/>"""
        assertTrue(
            XmlLintRules.attributeProblems(parse(xml), "res/layout/a.xml", checker).isEmpty(),
            "xmlns:*, tools:* and unprefixed attributes are never checked",
        )
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = Fake
        override val version: Long = 1
        override fun length(): Int = text.length
    }

    private object Fake : VirtualFile {
        override val path = "res/layout/a.xml"; override val name = "a.xml"
        override val isDirectory = false; override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
