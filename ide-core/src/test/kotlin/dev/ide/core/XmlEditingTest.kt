package dev.ide.core

import dev.ide.ui.editor.XmlEditing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure auto-close-tag logic (the editor calls this for `.xml` files when `>` is typed). */
class XmlEditingTest {

    /** `|` marks the caret (just after the typed `>`). */
    private fun close(src: String): String? {
        val caret = src.indexOf('|')
        return XmlEditing.tagToClose(src.removeRange(caret, caret + 1), caret)
    }

    @Test fun closesAStartTag() = assertEquals("TextView", close("<TextView>|"))
    @Test fun closesAStartTagWithAttributes() =
        assertEquals("LinearLayout", close("<LinearLayout android:orientation=\"vertical\">|"))

    @Test fun ignoresSelfClosing() = assertNull(close("<View/>|"))
    @Test fun ignoresClosingTag() = assertNull(close("<a></a>|"))
    @Test fun ignoresComment() = assertNull(close("<!-- hi -->|"))

    @Test fun doesNotReFireWhenAlreadyFollowedByTag() {
        // Caret sits between the just-inserted `>` and the auto-added close tag.
        assertNull(XmlEditing.tagToClose("<TextView></TextView>", 10))
    }

    @Test fun handlesAttributeValueContainingGt() {
        // A `>` typed inside the quoted value must not be treated as a tag close.
        assertNull(close("<TextView android:text=\"a >| b\""))
    }
}
