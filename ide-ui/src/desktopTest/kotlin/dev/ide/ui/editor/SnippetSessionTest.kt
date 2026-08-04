package dev.ide.ui.editor

import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.UiSnippet
import dev.ide.ui.backend.UiSnippetStop
import dev.ide.ui.backend.UiTextRange
import dev.ide.ui.editor.core.EditorSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SnippetSession] navigation contract — the behavior the editor's template stepping (Tab, and now Enter /
 * accepting a completion) depends on: [SnippetSession.next] advances stop to stop, mirrors a linked placeholder
 * as it leaves it, and returns false while landing the caret at `$0` once the stops run out (so the caller
 * ends the session on that final step instead of the key falling through to a newline).
 */
class SnippetSessionTest {

    /** An [EditorSession] over [text] with a [SnippetSession] started at offset 0, wired exactly as the editor
     *  wires it (`onSnippetEdit` re-anchors on every edit). */
    private fun start(text: String, snippet: UiSnippet): Pair<EditorSession, SnippetSession> {
        val session = EditorSession(text, CodeLanguage.Kotlin, TextRange(0))
        val snip = SnippetSession.start(session, 0, snippet)!!
        session.onSnippetEdit = { snip.onEdit(it) }
        return session to snip
    }

    @Test
    fun nextWalksStopsThenFinishesAtFinalCaret() {
        // "AAA BBB CCC": stop 1 = AAA[0,3], stop 2 = BBB[4,7], $0 at end (11).
        val (session, snip) = start(
            "AAA BBB CCC",
            UiSnippet(
                stops = listOf(
                    UiSnippetStop(1, listOf(UiTextRange(0, 3))),
                    UiSnippetStop(2, listOf(UiTextRange(4, 7))),
                ),
                finalCaretOffset = 11,
            ),
        )
        // start() selected the first stop.
        assertEquals(TextRange(0, 3), session.selection, "starts on the first field")

        assertTrue(snip.next(), "advances to the second field")
        assertEquals(TextRange(4, 7), session.selection, "second field selected")

        assertFalse(snip.next(), "no stops left → returns false so the caller ends the session")
        assertEquals(TextRange(11), session.selection, "caret lands at \$0 (finalCaret), NOT a new line")
    }

    @Test
    fun leavingALinkedStopMirrorsTheEditedPlaceholder() {
        // "fn(p, p)": one linked stop with the two `p`s ([3,4] primary, [6,7] mirror); $0 at end (8).
        val (session, snip) = start(
            "fn(p, p)",
            UiSnippet(
                stops = listOf(UiSnippetStop(1, listOf(UiTextRange(3, 4), UiTextRange(6, 7)))),
                finalCaretOffset = 8,
            ),
        )
        assertEquals(TextRange(3, 4), session.selection, "starts on the primary occurrence")

        // Rename the placeholder in place (as typing into the field would) — the session re-anchors via onSnippetEdit.
        session.replaceRange(3, 4, "arg", TextRange(6))

        // Stepping off the stop mirrors the edited primary into the linked occurrence, then finishes at $0.
        assertFalse(snip.next(), "single stop → next() finishes")
        assertEquals("fn(arg, arg)", session.doc.text, "the linked occurrence was rewritten to match")
    }
}
