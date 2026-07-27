package dev.ide.ui.editor.core

import androidx.compose.ui.text.TextRange
import dev.ide.ui.editor.CodeLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A read-only [EditorSession] (`editable = false`, used for decompiled / library-source views) rejects every
 * content mutation — typing, IME commit, backspace/delete, and a raw [EditorSession.replaceRange] — while
 * caret navigation stays live so the text can still be read, selected, and copied.
 */
class EditorSessionReadOnlyTest {

    private fun readOnly(text: String, caret: Int = 0): EditorSession =
        EditorSession(text, CodeLanguage.Java, TextRange(caret), editable = false)

    @Test
    fun typingIsInert() {
        val s = readOnly("class Foo {}", caret = 5)
        s.commitText("X")
        assertEquals("class Foo {}", s.doc.text, "typing into a read-only buffer must not change it")
    }

    @Test
    fun backspaceAndDeleteAreInert() {
        val s = readOnly("abc", caret = 2)
        s.backspace()
        s.deleteForward()
        assertEquals("abc", s.doc.text, "backspace/delete must not change a read-only buffer")
    }

    @Test
    fun replaceRangeIsInert() {
        val s = readOnly("hello world", caret = 0)
        s.replaceRange(0, 5, "HELLO", TextRange(5))
        assertEquals("hello world", s.doc.text, "an explicit replaceRange must be a no-op when read-only")
    }

    @Test
    fun caretStillMoves() {
        val s = readOnly("abcdef", caret = 0)
        s.setCaret(4)
        assertEquals(4, s.selection.start, "caret navigation stays live in a read-only buffer")
    }

    @Test
    fun anEditableSessionStillMutates() {
        val s = EditorSession("abc", CodeLanguage.Java, TextRange(3)) // default editable = true
        s.commitText("d")
        assertEquals("abcd", s.doc.text, "the guard must not affect a normal editable session")
    }
}
