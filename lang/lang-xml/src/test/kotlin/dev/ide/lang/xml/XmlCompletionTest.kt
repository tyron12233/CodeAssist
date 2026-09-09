package dev.ide.lang.xml

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.complete
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.xml.completion.XmlCompletionKind
import dev.ide.lang.xml.completion.XmlCompletionPosition
import dev.ide.lang.xml.completion.XmlContextScanner
import dev.ide.lang.xml.completion.XmlCompletion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlCompletionTest {

    /** Caret is marked by `|` in the source; returns (text-without-bar, offset). */
    private fun at(src: String): Pair<String, Int> {
        val i = src.indexOf('|')
        return src.removeRange(i, i + 1) to i
    }

    private fun positionAt(src: String): XmlCompletionPosition {
        val (text, offset) = at(src)
        val parsed = parse(text)
        return XmlContextScanner.scan(text, offset, parsed, "res/layout/a.xml")
    }

    @Test
    fun detectsTagNameContext() {
        val pos = positionAt("<LinearLayout>\n  <Tex|\n</LinearLayout>")
        assertEquals(XmlCompletionKind.TAG_NAME, pos.kind)
        assertEquals("Tex", pos.prefix)
        assertEquals("LinearLayout", pos.parentTag)
    }

    @Test
    fun detectsAttributeNameContext() {
        val pos = positionAt("<TextView android:lay|")
        assertEquals(XmlCompletionKind.ATTRIBUTE_NAME, pos.kind)
        assertEquals("android:lay", pos.prefix)
        assertEquals("TextView", pos.tag)
    }

    @Test
    fun detectsAttributeValueContextWithResourcePrefix() {
        val pos = positionAt("<TextView android:text=\"@string/ho|\"")
        assertEquals(XmlCompletionKind.ATTRIBUTE_VALUE, pos.kind)
        assertEquals("android:text", pos.attributeName)
        assertEquals("@string/ho", pos.prefix)
        assertEquals("TextView", pos.tag)
    }

    @Test
    fun detectsValuePositionRightAfterEquals() {
        val pos = positionAt("<TextView android:id=|")
        assertEquals(XmlCompletionKind.ATTRIBUTE_VALUE, pos.kind)
        assertEquals("android:id", pos.attributeName)
    }

    @Test
    fun existingAttributesAreReported() {
        val pos = positionAt("<TextView android:id=\"@+id/x\" android:tex|")
        assertEquals(XmlCompletionKind.ATTRIBUTE_NAME, pos.kind)
        assertTrue("android:id" in pos.existingAttributes)
        assertTrue("android:tex" !in pos.existingAttributes) // the token being typed isn't "existing"
    }

    @Test
    fun matchesAttributeLocalNameWithoutNamespacePrefix() = runTest {
        // Typing `layout_w` (no `android:`) should still match `android:layout_width`.
        val contributor = dev.ide.lang.xml.completion.XmlCompletionContributor {
            listOf("android:layout_width", "android:layout_height", "android:text").map {
                CompletionItem(label = it, insertText = "$it=\"\"", kind = CompletionItemKind.FIELD)
            }
        }
        val service = XmlCompletion(contributors = { listOf(contributor) })
        val (text, offset) = at("<TextView layout_w|")
        val result = service.complete(CompletionRequest(TestDoc(text), offset, CompletionTrigger.Explicit))
        val labels = result.items.map { it.label }
        assertEquals(listOf("android:layout_width"), labels) // only the one whose local name matches
    }

    @Test
    fun nameMatchesIsNamespaceAndPathAware() {
        assertTrue(XmlCompletion.nameMatches("android:layout_width", "layout_w"))
        assertTrue(XmlCompletion.nameMatches("android:layout_width", "android:lay"))
        assertTrue(XmlCompletion.nameMatches("@string/home", "home"))
        assertTrue(!XmlCompletion.nameMatches("android:layout_width", "height"))
    }

    @Test
    fun serviceMergesAndPrefixFiltersContributors() = runTest {
        val contributor = dev.ide.lang.xml.completion.XmlCompletionContributor { p ->
            if (p.kind != XmlCompletionKind.TAG_NAME) emptyList()
            else listOf("TextView", "TableLayout", "Button").map {
                CompletionItem(label = it, insertText = it, kind = CompletionItemKind.CLASS)
            }
        }
        val service = XmlCompletion(contributors = { listOf(contributor) })
        val (text, offset) = at("<LinearLayout><T|></LinearLayout>")
        val result = service.complete(CompletionRequest(TestDoc(text), offset, CompletionTrigger.Explicit))
        val labels = result.items.map { it.label }
        assertEquals(listOf("TableLayout", "TextView"), labels) // "Button" filtered by the "T" prefix, sorted
    }
}
