package dev.ide.core

import dev.ide.ui.editor.XmlEditing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure auto-close-tag logic. The editor calls this from the typing path (smartInsert) the moment the
 * user types `>` in an `.xml` file — so `|` marks where that `>` keystroke lands, and the buffer fed in does
 * NOT yet contain it.
 */
class XmlEditingTest {

    private fun close(src: String): String? {
        val at = src.indexOf('|')
        return XmlEditing.tagToCloseOnType(src.removeRange(at, at + 1), at)
    }

    @Test fun closesAStartTag() = assertEquals("TextView", close("<TextView|"))
    @Test fun closesAStartTagWithAttributes() =
        assertEquals("LinearLayout", close("<LinearLayout android:orientation=\"vertical\"|"))

    @Test fun ignoresSelfClosing() = assertNull(close("<View/|"))
    @Test fun ignoresClosingTag() = assertNull(close("</a|"))
    @Test fun ignoresComment() = assertNull(close("<!-- hi --|"))
    @Test fun ignoresProcessingInstruction() = assertNull(close("<?xml version=\"1.0\"?|"))

    @Test fun ignoresGtInsideAttributeValue() {
        // The `>` is typed INSIDE a quoted value — not a tag boundary, so nothing is auto-closed.
        assertNull(close("<TextView android:text=\"a | b\""))
    }

    @Test fun closesEvenWhenAnAttributeValueContainsGt() {
        // A `>` already present inside an earlier attribute value must not fool the backward scan.
        assertEquals("TextView", close("<TextView android:text=\"a > b\"|"))
    }

    @Test fun doesNotDuplicateAnAlreadyClosedTag() {
        // The element already has its `</LinearLayout>` ahead (e.g. re-typing `>` after editing attributes).
        assertNull(close("<LinearLayout|\n  <TextView/>\n</LinearLayout>"))
    }

    @Test fun closesWhenOnlyNestedSameNameClosesAreAhead() {
        // The `</b>` ahead pairs with the NESTED `<b>`, not the one being opened → still auto-close.
        assertEquals("b", close("<b|<b></b>"))
    }

    @Test fun closesAWhollyNewTagBeforeSiblings() =
        assertEquals("TextView", close("<TextView|\n<Button></Button>"))

    // ---- linked tag editing: renaming the open tag rewrites the matching close tag ----

    /** `|` marks the caret inside the open tag's (already-edited) name; returns the resulting buffer or null. */
    private fun rename(src: String): String? {
        val caret = src.indexOf('|')
        val text = src.removeRange(caret, caret + 1)
        val edit = XmlEditing.linkedTagRenameEdit(text, caret) ?: return null
        return text.substring(0, edit.start) + edit.text + text.substring(edit.end)
    }

    @Test fun syncsCloseTagToOpenTagName() =
        assertEquals("<MyView></MyView>", rename("<MyView|></TextView>"))

    @Test fun syncsCloseTagWithNestedContent() =
        assertEquals("<Box>\n  <Button/>\n</Box>", rename("<Box|>\n  <Button/>\n</TextView>"))

    @Test fun ignoresNestedSameNameDepthWhenPairing() =
        // The first `</span>` pairs with the nested `<span>`; the OUTER close (`</old>`) is the one synced.
        assertEquals("<box><span></span></box>", rename("<box|><span></span></old>"))

    @Test fun noEditWhenNamesAlreadyMatch() = assertNull(rename("<TextView|></TextView>"))
    @Test fun noEditFromInsideAttributes() = assertNull(rename("<TextView text=\"a|\"></TextView>"))
    @Test fun noEditForSelfClosingTag() = assertNull(rename("<TextView|/>"))
    @Test fun noEditWhenCaretInCloseTag() = assertNull(rename("<a></a|>"))
    @Test fun noEditWhenNoCloseTagYet() = assertNull(rename("<TextView|>\n  text"))

    @Test fun noEditForUnterminatedOpenTagAboveClosedElement() =
        // Typing `<a` on a new line above an already-closed <intent-filter> must NOT hijack its close tag:
        // `<a` has no `>` of its own, so it pairs with nothing (its `>` isn't the later tag's `>`).
        assertNull(rename("<a|\n<intent-filter>\n</intent-filter>"))

    @Test fun noEditForBareLessThanAboveClosedElement() =
        // The reported case: a lone `<` with the caret just after it, above a well-formed element.
        assertNull(rename("<|\n<intent-filter>\n</intent-filter>"))
}
