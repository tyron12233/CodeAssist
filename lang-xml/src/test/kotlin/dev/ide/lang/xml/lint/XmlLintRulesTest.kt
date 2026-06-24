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
