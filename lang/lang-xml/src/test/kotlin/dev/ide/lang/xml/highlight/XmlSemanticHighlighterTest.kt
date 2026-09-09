package dev.ide.lang.xml.highlight

import dev.ide.lang.xml.FakeFile
import dev.ide.lang.xml.parse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlSemanticHighlighterTest {

    @Test
    fun colorsNamespacePrefixesAndResourceReferences() = runTest {
        val xml = """<TextView xmlns:android="http://x" android:text="@string/app_name" android:layout_width="match_parent"/>"""
        val parsed = parse(xml)
        val tokens = XmlSemanticHighlighter { parsed }.highlight(FakeFile("res/layout/a.xml"))

        // Every namespaced attribute name contributes a prefix token (`xmlns`, `android`, `android`).
        val ns = tokens.filter { it.kind.id == "xmlNamespace" }.map { xml.substring(it.range.start, it.range.end) }
        assertEquals(listOf("xmlns", "android", "android"), ns, "prefix tokens, in document order")

        // The `@string/app_name` value is a reference; `match_parent` is a plain literal (no token).
        val refs = tokens.filter { it.kind.id == "xmlReference" }
        assertEquals(1, refs.size)
        assertEquals("@string/app_name", xml.substring(refs[0].range.start, refs[0].range.end))
    }

    @Test
    fun colorsThemeAttributeReferencesAndIgnoresLiterals() = runTest {
        val xml = """<View xmlns:android="http://x" android:background="?attr/colorPrimary" android:visibility="gone"/>"""
        val parsed = parse(xml)
        val refs = XmlSemanticHighlighter { parsed }.highlight(FakeFile("res/layout/a.xml"))
            .filter { it.kind.id == "xmlReference" }.map { xml.substring(it.range.start, it.range.end) }
        assertEquals(listOf("?attr/colorPrimary"), refs, "a ?attr/ theme ref is colored; a plain enum value is not")
    }
}
