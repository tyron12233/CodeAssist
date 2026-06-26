package dev.ide.ui.editor.core

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.XmlEditing
import dev.ide.ui.editor.applySmartEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [EditorSession] edit semantics: smart-edit parity with the legacy path, IME flows, host sync. */
class EditorSessionTest {

    private fun session(text: String, caret: Int = text.length, language: CodeLanguage = CodeLanguage.Java): EditorSession =
        EditorSession(text, language, TextRange(caret))

    // ---- smart-edit parity: typing a char through the session == applySmartEdit over BasicTextField values ----

    private fun assertTypingParity(text: String, caret: Int, ch: Char) {
        val s = session(text, caret)
        s.commitText(ch.toString())

        val old = TextFieldValue(text, TextRange(caret))
        val rawText = text.substring(0, caret) + ch + text.substring(caret)
        val raw = TextFieldValue(rawText, TextRange(caret + 1))
        val expected = applySmartEdit(old, raw, CodeLanguage.Java)

        assertEquals(expected.text, s.doc.text, "text after typing '$ch' into \"$text\" at $caret")
        assertEquals(expected.selection, s.selection, "caret after typing '$ch'")
    }

    @Test
    fun smartParity() {
        assertTypingParity("foo", 3, '(')            // auto-close
        assertTypingParity("foo()", 4, ')')          // skip-over
        assertTypingParity("val s = ", 8, '"')       // quote auto-close
        assertTypingParity("ab", 1, 'x')             // plain insert
        assertTypingParity("if (x) {}", 8, '\n')     // pair expansion on Enter
        assertTypingParity("    indented", 12, '\n') // indent continuation
        assertTypingParity("a(b", 3, ')')            // no skip (nothing to skip over)
    }

    @Test
    fun smartBraceParity() {
        // typing `{` where a stray closer already exists must NOT auto-close (it would dangle)
        assertTypingParity("fun main() \n  println(\"x\")\n}", 11, '{')
        // typing `{` in a balanced doc DOES auto-close (its own pair is needed)
        assertTypingParity("class A {\n    if (x) \n}", 21, '{')
        // empty doc: auto-close
        assertTypingParity("", 0, '{')
    }

    /** The smart heuristic: typing `{` before an already-unmatched `}` inserts a lone `{`, not a pair. */
    @Test
    fun typingBraceBeforeUnmatchedCloserDoesNotAutoClose() {
        val s = session("fun main() \n  println()\n}", 11)
        s.commitText("{")
        assertEquals("fun main() {\n  println()\n}", s.doc.text)
        assertEquals(TextRange(12), s.selection)
    }

    /** With braces balanced, the nested `{` still gets its own auto-closed pair. */
    @Test
    fun typingBraceInBalancedDocAutoCloses() {
        val s = session("class A {\n    if (x) \n}", 21)
        s.commitText("{")
        assertEquals("class A {\n    if (x) {}\n}", s.doc.text)
        assertEquals(TextRange(22), s.selection)
    }

    // ---- XML tag auto-close: a `>` keystroke on an open tag inserts the matching close, atomically ----

    /** Typing `>` after `<TextView` (xml file) inserts `</TextView>` and parks the caret between them. */
    @Test
    fun typingGtAutoClosesXmlTag() {
        val s = session("<TextView", language = CodeLanguage.Xml)
        s.commitText(">")
        assertEquals("<TextView></TextView>", s.doc.text)
        assertEquals(TextRange(10), s.selection, "caret sits between the open and close tags")
    }

    /** The regression this fixes: deleting back to a `>` (a text edit, not a `>` keystroke) must NOT re-close. */
    @Test
    fun deletingDoesNotReCloseAnXmlTag() {
        // `<a>|</a>` with the caret right after the `>` — backspace deletes the `>`, then forward-delete is moot.
        val s = session("<a>X</a>", caret = 4, language = CodeLanguage.Xml) // caret after the stray 'X'
        s.backspace()                                                       // remove 'X' → caret lands after `>`
        assertEquals("<a></a>", s.doc.text, "no phantom close tag is inserted by the deletion")
    }

    /** Typing `>` on an element that already has its close tag ahead doesn't duplicate the closer. */
    @Test
    fun typingGtDoesNotDuplicateAnExistingCloseTag() {
        val s = session("<LinearLayout</LinearLayout>", caret = 13, language = CodeLanguage.Xml)
        s.commitText(">")
        assertEquals("<LinearLayout></LinearLayout>", s.doc.text)
    }

    /** A `>` typed in a non-xml file is just a character (no tag machinery). */
    @Test
    fun typingGtInJavaInsertsAPlainChar() {
        val s = session("a -", caret = 3, language = CodeLanguage.Java)
        s.commitText(">")
        assertEquals("a ->", s.doc.text)
    }

    /** Linked tag editing: applying the rename edit syncs the close tag and leaves the caret in the open tag. */
    @Test
    fun linkedRenameSyncsCloseTagAndKeepsCaret() {
        // The exact call the editor's per-edit effect makes after the user edits an open tag's name.
        val s = session("<Box></TextView>", caret = 4, language = CodeLanguage.Xml) // caret just after "<Box"
        val edit = XmlEditing.linkedTagRenameEdit(s.doc.chars, s.selection.start)!!
        s.applyEdits(listOf(edit), TextRange(s.selection.start))
        assertEquals("<Box></Box>", s.doc.text)
        assertEquals(TextRange(4), s.selection, "the caret stays in the open tag (the edit was entirely after it)")
    }

    // ---- inlay hints are session-owned overlays now (set by the daemon), shifted in place like diagnostics ----

    private fun hint(offset: Int) = UiInlayHint(offset, listOf(UiInlayPart(": Int")), UiInlayKind.Type)

    @Test
    fun inlayHintsShiftWhenTypingBeforeThem() {
        val s = session("val x = 1\n", caret = 0)
        s.applyInlayHints(listOf(hint(5))) // a `: Int` hint after `x`
        s.commitText("abc")                // insert 3 chars at offset 0
        assertEquals(8, s.inlayHints.single().offset, "a hint after the edit shifts by the inserted length")
    }

    @Test
    fun inlayHintInsideAnEditIsDropped() {
        val s = session("val xy = 1\n", caret = 0)
        s.applyInlayHints(listOf(hint(5)))    // anchored at offset 5 (between x and y)
        s.replaceRange(4, 6, "", TextRange(4)) // delete "xy" — the anchor was inside the removed span
        assertTrue(s.inlayHints.isEmpty(), "a hint whose anchor the edit consumed is dropped (refetch repositions)")
    }

    @Test
    fun backspaceSnapsOverIndentedCloserToOpenerIndent() {
        // The `}` closing f() is over-indented at 8 spaces; one Backspace snaps it to the `fun f()` indent (4).
        val code = "class A {\n    fun f() {\n        body()\n        }\n}"
        val caret = code.indexOf("        }\n}") + 8 // just before the over-indented `}`
        val s = session(code, caret, language = CodeLanguage.Kotlin)
        s.backspace()
        assertEquals("class A {\n    fun f() {\n        body()\n    }\n}", s.doc.text)
        assertEquals(TextRange(code.indexOf("        }\n}") + 4), s.selection)
    }

    @Test
    fun backspaceOnAlignedCloserKeepsItAtOpenerIndent() {
        // The user's exact case: `}` already aligned with its opener (`onCreate`/`f()` at 4). Backspace must
        // NOT de-indent it below the opener (the reported jump to column 0); the brace stays put.
        val code = "class A {\n    fun f() {\n        body()\n    }\n}"
        val caret = code.indexOf("    }\n}") + 4
        val s = session(code, caret, language = CodeLanguage.Kotlin)
        s.backspace()
        assertEquals(code, s.doc.text)
        assertEquals(TextRange(caret), s.selection)
    }

    @Test
    fun backspaceSnapsCloserAcrossBlankLineToOpener() {
        // A blank line above + an over-indented closer: collapse the gap AND align `}` to the `fun f()` indent
        // (4), not the previous content line `body()` (8).
        val code = "class A {\n    fun f() {\n        body()\n\n        }\n}"
        val caret = code.indexOf("        }\n}") + 8
        val s = session(code, caret, language = CodeLanguage.Kotlin)
        s.backspace()
        assertEquals("class A {\n    fun f() {\n        body()\n    }\n}", s.doc.text)
    }

    @Test
    fun backspaceRemovesEmptyPair() {
        val s = session("foo()", 4)
        s.backspace()
        assertEquals("foo", s.doc.text)
        assertEquals(TextRange(3), s.selection)
    }

    @Test
    fun backspaceOnBlankIndentedLineRemovesWholeIndentAndJoins() {
        // caret sits at the end of a whitespace-only line; one Backspace hops to the prev line's end
        val s = session("    foo\n        ", 16)
        s.backspace()
        assertEquals("    foo", s.doc.text)
        assertEquals(TextRange(7), s.selection)
    }

    @Test
    fun backspaceOnBlankLineWithTabsRemovesAll() {
        val s = session("bar\n\t\t\t", 7)
        s.backspace()
        assertEquals("bar", s.doc.text)
        assertEquals(TextRange(3), s.selection)
    }

    @Test
    fun backspaceMidIndentOfBlankLineRemovesWholeLineAndPutsCaretAtPrevLineEnd() {
        // caret sits amid the tabs of a blank line (e.g. inside an auto-inserted `{\n\t\t\n}` block);
        // deleting must take the trailing tabs too, so the joined line has no leftover whitespace and
        // the caret lands at the end of the previous line.
        val s = session("foo\n\t\t\t", 5) // caret after the first tab, two more follow
        s.backspace()
        assertEquals("foo", s.doc.text)
        assertEquals(TextRange(3), s.selection)
    }

    @Test
    fun backspaceInIndentOfNonBlankLinePeelsOneChar() {
        // line has real content after the indent — stays a normal single-char delete
        val s = session("foo\n    bar", 7)
        s.backspace()
        assertEquals("foo\n   bar", s.doc.text)
        assertEquals(TextRange(6), s.selection)
    }

    @Test
    fun backspaceOnBlankFirstLineHasNoPrevLineToJoin() {
        // no preceding line — falls back to a plain one-char delete
        val s = session("    ", 4)
        s.backspace()
        assertEquals("   ", s.doc.text)
        assertEquals(TextRange(3), s.selection)
    }

    @Test
    fun selectionTypingReplaces() {
        val s = session("hello world", 0)
        s.setSelectionRange(0, 5)
        s.commitText("X")
        assertEquals("X world", s.doc.text)
        assertEquals(TextRange(1), s.selection)
    }

    // ---- IME flows ----

    @Test
    fun composingTextReplacesItsRegion() {
        val s = session("ab ", 3)
        s.imeSetComposingText("he", 1)
        assertEquals("ab he", s.doc.text)
        assertEquals(TextRange(3, 5), s.composing)
        assertEquals(TextRange(5), s.selection)
        s.imeSetComposingText("hello", 1) // IME re-sends the grown composition
        assertEquals("ab hello", s.doc.text)
        assertEquals(TextRange(3, 8), s.composing)
        s.imeCommitText("hello!", 1)
        assertEquals("ab hello!", s.doc.text)
        assertNull(s.composing)
        assertEquals(TextRange(9), s.selection)
    }

    @Test
    fun imeNewlineCommitMidCompositionKeepsTheComposedText() {
        // Enter pressed while a word is being composed must finish the composition and drop a newline after it —
        // not overwrite the composing region with the "\n" (which would delete the just-composed word).
        val s = session("", 0)
        s.imeSetComposingText("ab", 1)
        assertEquals("ab", s.doc.text)
        assertNotNull(s.composing)
        s.imeCommitText("\n", 1)
        assertNull(s.composing, "Enter finishes the composition")
        assertTrue(s.doc.text.startsWith("ab\n"), "the composed word survives and a newline follows: \"${s.doc.text}\"")
    }

    @Test
    fun deleteSurroundingIsBackspace() {
        // Gboard backspace = deleteSurroundingText(1, 0) — must stay pair-aware
        val t = session("foo()", 4)
        t.imeDeleteSurrounding(1, 0)
        assertEquals("foo", t.doc.text)
        assertEquals(TextRange(3), t.selection)
    }

    @Test
    fun deleteSurroundingBothSides() {
        val s = session("abcdef", 3)
        s.imeDeleteSurrounding(2, 2)
        assertEquals("af", s.doc.text)
        assertEquals(TextRange(1), s.selection)
    }

    @Test
    fun batchedImeEditsPushImeOnce() {
        // The session has no host text mirror to coalesce anymore; what a batch coalesces is the IME push.
        val s = session("x", 1)
        var imePushes = 0
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() { imePushes++ }
            override fun onRestartInput() {}
        }
        s.beginBatch()
        s.imeSetComposingText("ab", 1)
        s.imeSetComposingText("abc", 1)
        s.endBatch()
        assertEquals(1, imePushes)
        assertEquals("xabc", s.doc.text)
    }

    @Test
    fun textRevisionBumpsOnTextEditsOnly() {
        val s = session("abc", 0)
        val r0 = s.textRevision
        s.setCaret(2)                 // selection move — no text change
        assertEquals(r0, s.textRevision)
        s.commitText("x")             // text edit
        assertEquals(r0 + 1, s.textRevision)
    }

    @Test
    fun onTextEditReportsTheSpan() {
        val s = session("hello", 5)
        val spans = ArrayList<Triple<Int, Int, Int>>()
        s.onTextEdit = { spans.add(Triple(it.start, it.removed, it.added)) }
        s.commitText("!")             // insert 1 char at offset 5
        s.setCaret(0)                 // pure caret move — must NOT fire onTextEdit
        s.setSelectionRange(0, 6)
        s.commitText("")              // replace selection [0,6) with "" → delete 6
        assertEquals(listOf(Triple(5, 0, 1), Triple(0, 6, 0)), spans)
    }

    // ---- external replacement ----

    @Test
    fun setTextReplacesBufferAndBumpsRevision() {
        val s = session("abc", 3)
        val r0 = s.textRevision
        s.setText("totally new\ncontent", TextRange(5))
        assertEquals("totally new\ncontent", s.doc.text)
        assertEquals(2, s.doc.lineCount)
        assertEquals(TextRange(5), s.selection)
        assertEquals(r0 + 1, s.textRevision, "an out-of-band replace must re-trigger analysis")
    }

    // ---- diagnostics are spans the session shifts itself ----

    @Test
    fun editorShiftsDiagnosticsLikeSpans() {
        // A diagnostic on "baz" [8,11) in "foo bar baz". Typing before it must push it right — the session
        // shifts its own diagnostics in [replaceRange], exactly as it shifts the line index and token spans.
        val s = session("foo bar baz", 0)
        s.applyAnalysis(listOf(UiDiagnostic(UiSeverity.Error, 1, 9, "bad", startOffset = 8, endOffset = 11)))
        s.commitText("XY") // insert 2 chars at offset 0 → "XYfoo bar baz"
        val d = s.diagnostics.single()
        assertEquals(10, d.startOffset, "diagnostic start shifts by the insert length")
        assertEquals(13, d.endOffset, "diagnostic end shifts by the insert length")

        // an edit strictly inside the range stretches it (start holds, end moves); typing at offset 11 is
        // between the 'b' and 'a' of "baz" (range is now [10,13))
        s.setSelectionRange(11, 11)
        s.commitText("zzz") // inside the range → it grows by 3
        assertEquals(10, s.diagnostics.single().startOffset)
        assertEquals(16, s.diagnostics.single().endOffset)

        // a wholesale replace drops the now-meaningless anchors (re-analysis refills)
        s.setText("totally different")
        assertTrue(s.diagnostics.isEmpty())
    }

    // ---- caret navigation ----

    @Test
    fun wordNavAndVerticalGoalColumn() {
        val s = session("alpha beta\nx\nlonger line", 0)
        s.moveHorizontal(1, select = false, word = true)
        assertEquals(TextRange(5), s.selection) // end of "alpha"
        s.moveLineEnd(select = false)
        assertEquals(TextRange(10), s.selection)
        s.moveVertical(1, select = false) // down to "x" — clamped to its length
        assertEquals(TextRange(12), s.selection)
        s.moveVertical(1, select = false) // down to "longer line" — goal column 10 restored
        val line2Start = s.doc.lineStart(2)
        assertEquals(TextRange(line2Start + 10), s.selection)
    }

    @Test
    fun selectWordAndSelectedText() {
        val s = session("foo barBaz qux", 0)
        s.selectWordAt(6)
        assertEquals("barBaz", s.selectedText())
    }

    @Test
    fun completionAcceptWhileComposingRestartsTheIme() {
        // The Android stale-prefix bug: the user types a prefix the IME holds as a COMPOSING region, then
        // accepts a completion. Clearing our composing + updating selection isn't enough — the IME keeps its own
        // composing buffer (the prefix), re-inserting it on the next keystroke. `applyEdits` must restart the IME
        // so it drops that buffer.
        val s = session("", 0)
        var restarts = 0
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() {}
            override fun onRestartInput() { restarts++ }
        }
        s.imeSetComposingText("pri", 1) // IME inserts + composes "pri"
        assertNotNull(s.composing)
        assertEquals("pri", s.doc.text)
        s.applyEdits(listOf(RangeEdit(0, 3, "println", 7)), TextRange(7)) // accept the completion
        assertEquals("println", s.doc.text)
        assertNull(s.composing, "the editor's composing region must be cleared")
        assertEquals(1, restarts, "a completion accepted while composing must restart the IME")
    }

    @Test
    fun completionAcceptThatMovesCaretInsideInsertedTextRestartsAtFinalCaret() {
        // A method completion (`println()`) accepted while composing "pri": it inserts more than the identifier
        // (the `()`) AND moves the caret INSIDE the parens. The IME restart must fire with the FINAL caret
        // already in place, so the keyboard re-reads the post-accept position (8, inside the parens) — not the
        // end of the inserted text — and drops its stale "pri" buffer.
        val s = session("", 0)
        var restarts = 0
        var caretSeenOnRestart = -1
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() {}
            override fun onRestartInput() { restarts++; caretSeenOnRestart = s.selection.start }
        }
        s.imeSetComposingText("pri", 1)
        s.applyEdits(listOf(RangeEdit(0, 3, "println()", 9)), TextRange(8)) // caret lands inside the parens
        assertEquals("println()", s.doc.text)
        assertEquals(TextRange(8), s.selection, "caret lands inside the parens")
        assertNull(s.composing)
        assertEquals(1, restarts)
        assertEquals(8, caretSeenOnRestart, "the restart sees the final (moved) caret, not the end of the insert")
    }

    @Test
    fun completionAcceptWithSelectedPlaceholderRestartsTheIme() {
        // A snippet-style accept that leaves a placeholder SELECTED (a non-collapsed final selection): the
        // restart still fires and the IME re-reads the selection range.
        val s = session("", 0)
        var restarts = 0
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() {}
            override fun onRestartInput() { restarts++ }
        }
        s.imeSetComposingText("fo", 1)
        s.applyEdits(listOf(RangeEdit(0, 2, "forEach { it }", 14)), TextRange(9, 11)) // "it" selected
        assertEquals("forEach { it }", s.doc.text)
        assertEquals(TextRange(9, 11), s.selection)
        assertEquals(1, restarts, "a placeholder-selecting accept while composing must still restart the IME")
    }

    @Test
    fun applyEditsWithoutComposingDoesNotRestartTheIme() {
        // A non-IME edit through the same path (auto-close bracket, block edit, or a completion accepted with no
        // active composition) must NOT churn the IME — only a stale composing buffer needs the restart.
        val s = session("foo", 3)
        var restarts = 0
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() {}
            override fun onRestartInput() { restarts++ }
        }
        s.applyEdits(listOf(RangeEdit(3, 3, "()", 4)), TextRange(4))
        assertEquals("foo()", s.doc.text)
        assertEquals(0, restarts, "an edit with no active composing region must not restart the IME")
    }

    // ---- typing-path IME resync: a smart edit shifts the buffer by a different amount than the keystroke the
    // IME delivered, so its model drifts; left unsynced a later absolute setSelection lands on the wrong line. ----

    private fun countingImeSession(text: String, caret: Int, language: CodeLanguage = CodeLanguage.Java): Pair<EditorSession, () -> Int> {
        val s = session(text, caret, language)
        var restarts = 0
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() {}
            override fun onRestartInput() { restarts++ }
        }
        return s to { restarts }
    }

    @Test
    fun plainTypingDoesNotRestartTheIme() {
        // The common case: typing a letter inserts exactly the char the IME delivered, so its model stays
        // coherent and we must NOT churn it (a restart would reset prediction/gesture state every keystroke).
        val (s, restarts) = countingImeSession("ab", 1)
        s.commitText("x")
        assertEquals("axb", s.doc.text)
        assertEquals(0, restarts(), "a plain insert must not restart the IME")
    }

    @Test
    fun autoCloseTypingRestartsTheIme() {
        val (s, restarts) = countingImeSession("foo", 3)
        s.commitText("(") // smartInsert auto-closes to "()" — one more char than the IME delivered
        assertEquals("foo()", s.doc.text)
        assertEquals(1, restarts(), "auto-closing a pair shifts the buffer the IME didn't author")
    }

    @Test
    fun skipOverTypingRestartsTheIme() {
        val (s, restarts) = countingImeSession("foo()", 4)
        s.commitText(")") // skip-over inserts nothing, just hops the caret
        assertEquals("foo()", s.doc.text)
        assertEquals(TextRange(5), s.selection)
        assertEquals(1, restarts(), "a skip-over inserts fewer chars than the IME delivered")
    }

    @Test
    fun smartEnterRestartsTheIme() {
        // The worst offender: Enter inserts "\n" + indent (here a three-line `{ }` expansion), so the IME's
        // cursor drifts by the whole indent on every newline. This is the dominant cause of the line jump.
        val (s, restarts) = countingImeSession("if (x) {}", 8)
        s.commitText("\n")
        assertEquals(1, restarts(), "a smart Enter adds indentation the IME didn't author")
    }

    @Test
    fun imeCommitOfComposedWordDoesNotRestart() {
        // The keyboard's own multi-char commit (a composed/gesture-typed word) matches the buffer exactly, so
        // it must NOT restart — that would break prediction continuity.
        val (s, restarts) = countingImeSession("", 0)
        s.imeSetComposingText("hello", 1)
        val before = restarts()
        s.imeCommitText("hello", 1)
        assertEquals("hello", s.doc.text)
        assertEquals(before, restarts(), "committing a composed word must not restart the IME")
    }

    @Test
    fun pairBackspaceRestartsButPlainBackspaceDoesNot() {
        val (plain, plainRestarts) = countingImeSession("abc", 2)
        plain.backspace()
        assertEquals("ac", plain.doc.text)
        assertEquals(0, plainRestarts(), "a plain single-char backspace matches deleteSurroundingText(1,0)")

        val (pair, pairRestarts) = countingImeSession("a()", 2) // caret inside the empty pair
        pair.backspace()
        assertEquals("a", pair.doc.text) // both chars of the pair go
        assertEquals(1, pairRestarts(), "a pair-aware backspace deletes more than the IME asked for")
    }

    // ---- continuous IME sync (the extracted-text upgrade): per-edit onTextChanged keeps a monitoring keyboard
    // exact, so a smart edit no longer needs the disruptive restart. ----

    @Test
    fun singleEditPushesAPartialOnTextChanged() {
        val s = session("ab", 1)
        var calls = 0
        var seen: EditSpan? = null
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() {}
            override fun onTextChanged(span: EditSpan?) { calls++; seen = span }
            override fun onRestartInput() {}
        }
        s.commitText("x")
        assertEquals(1, calls, "one text edit → one onTextChanged")
        val sp = seen
        assertNotNull(sp, "a single edit carries its span so the platform can push a PARTIAL extracted-text update")
        assertEquals(1, sp.start)
        assertEquals(0, sp.removed)
        assertEquals(1, sp.added)
    }

    @Test
    fun batchCoalescesToOneFullRefresh() {
        // A multi-edit op (completion accept here) defers its per-edit pushes and emits a single null-span
        // onTextChanged at the end — a full-snapshot refresh, not one partial per edit.
        val s = session("foo", 3)
        var fullRefreshes = 0
        var partials = 0
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() {}
            override fun onTextChanged(span: EditSpan?) { if (span == null) fullRefreshes++ else partials++ }
            override fun onRestartInput() {}
        }
        s.applyEdits(listOf(RangeEdit(0, 0, "import x\n", 0), RangeEdit(3, 3, "()", 0)), TextRange(5))
        assertEquals(1, fullRefreshes, "a batch pushes exactly one full-snapshot refresh")
        assertEquals(0, partials, "edits inside a batch don't each push a partial")
    }

    @Test
    fun monitoringImeIsNotRestartedOnSmartEdit() {
        // The whole point of the upgrade: an IME that mirrors our text stays coherent through the per-edit push,
        // so a smart edit (here auto-close) must NOT churn it with a restart.
        val s = session("foo", 3)
        var restarts = 0
        var textChanges = 0
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() {}
            override fun onTextChanged(span: EditSpan?) { textChanges++ }
            override fun onRestartInput() { restarts++ }
            override fun isSyncingExtractedText() = true
        }
        s.commitText("(")
        assertEquals("foo()", s.doc.text)
        assertTrue(textChanges >= 1, "the edit is pushed to the monitoring IME")
        assertEquals(0, restarts, "a monitoring IME is kept synced without a disruptive restart")
    }

    // ---- extracted-text snapshot arithmetic (the offset contract that, if wrong, lands the caret on the wrong
    // line). The android IME bridge copies these fields straight into ExtractedText, so testing them here covers
    // the bug-prone math on the JVM without an emulator. ----

    @Test
    fun fullSnapshotOfSmallBufferIsTheWholeText() {
        val snap = session("hello", 2).extractedTextSnapshot(maxChars = 1000)
        assertEquals("hello", snap.text)
        assertEquals(0, snap.startOffset)
        assertEquals(2, snap.selectionStart)
        assertEquals(2, snap.selectionEnd)
        assertEquals(-1, snap.partialStartOffset, "a full snapshot has no partial range")
        assertEquals(-1, snap.partialEndOffset)
    }

    @Test
    fun largeBufferSnapshotWindowsAroundTheCaret() {
        // 20 chars, caret at 10, window of 8 → start = (10-4) clamped to [0, 20-8] = 6, end = 14; selection relative.
        val snap = session("0123456789ABCDEFGHIJ", 10).extractedTextSnapshot(maxChars = 8)
        assertEquals(6, snap.startOffset)
        assertEquals("6789ABCD", snap.text)
        assertEquals(4, snap.selectionStart, "selection is relative to the window start")
        assertEquals(4, snap.selectionEnd)
    }

    /** Capture the snapshot the platform would push for the LAST edit, exactly as the android bridge does. */
    private fun snapshotAfter(before: String, caret: Int, build: (EditorSession) -> Unit): ExtractedTextSnapshot {
        val s = session(before, caret)
        var snap: ExtractedTextSnapshot? = null
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() {}
            override fun onTextChanged(span: EditSpan?) {
                snap = if (span == null) s.extractedTextSnapshot(1000) else s.partialExtractedSnapshot(span)
            }
            override fun onRestartInput() {}
        }
        build(s)
        return assertNotNull(snap)
    }

    @Test
    fun partialSnapshotForAutoClose() {
        // type '(' on "foo|" → buffer "foo()", caret between the parens (absolute offset 4). The IME replaces the
        // empty range [3,3) with "()"; the splice range and the caret it lands on are all absolute document offsets.
        val snap = snapshotAfter("foo", 3) { it.commitText("(") }
        assertEquals("()", snap.text)
        assertEquals(0, snap.startOffset)
        assertEquals(3, snap.partialStartOffset)
        assertEquals(3, snap.partialEndOffset)
        assertEquals(4, snap.selectionStart)
        assertEquals(4, snap.selectionEnd)
    }

    @Test
    fun skipOverIsASelectionOnlyUpdateNotATextChange() {
        // type ')' on "foo(|)" → no text changes, the caret just hops past the ')'. So it must NOT push an
        // extracted-text update (no onTextChanged); the IME learns the new caret via a plain selection push.
        val s = session("foo()", 4)
        var textChanges = 0
        var selectionPushes = 0
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() { selectionPushes++ }
            override fun onTextChanged(span: EditSpan?) { textChanges++ }
            override fun onRestartInput() {}
        }
        s.commitText(")")
        assertEquals("foo()", s.doc.text, "skip-over inserts nothing")
        assertEquals(TextRange(5), s.selection, "the caret advances past the closer")
        assertEquals(0, textChanges, "no text changed, so no extracted-text update")
        assertEquals(1, selectionPushes, "the caret move is a plain selection update")
    }

    @Test
    fun partialSnapshotForPairBackspace() {
        // backspace inside "a(|)" deletes both pair chars: replace [1,3) with "", caret at absolute offset 1.
        val snap = snapshotAfter("a()", 2) { it.backspace() }
        assertEquals("", snap.text)
        assertEquals(0, snap.startOffset)
        assertEquals(1, snap.partialStartOffset)
        assertEquals(3, snap.partialEndOffset, "both chars of the empty pair are in the replaced range")
        assertEquals(1, snap.selectionStart)
        assertEquals(1, snap.selectionEnd)
    }

    @Test
    fun partialSnapshotSplicesBackToTheNewBuffer() {
        // The core invariant: applying the partial (replace [partialStart,partialEnd) with text) to the PRE-EDIT
        // buffer must reproduce the post-edit buffer — i.e. the IME's mirror stays byte-identical to ours.
        val before = "if (x) {}"
        val s = session(before, 8) // caret inside the braces
        var snap: ExtractedTextSnapshot? = null
        s.imeListener = object : EditorSession.ImeListener {
            override fun onStateChanged() {}
            override fun onTextChanged(span: EditSpan?) { if (span != null) snap = s.partialExtractedSnapshot(span) }
            override fun onRestartInput() {}
        }
        s.commitText("\n") // smart Enter: inserts newline + indentation
        val sp = assertNotNull(snap)
        val reconstructed = before.substring(0, sp.partialStartOffset) + sp.text + before.substring(sp.partialEndOffset)
        assertEquals(s.doc.text, reconstructed, "the partial update reproduces our buffer in the IME's mirror")
    }

    @Test
    fun applyEditsWithAutoImport() {
        // completion accept: replace token at 20.. + insert an import line above
        val s = session("package p;\n\nclass A { Lis }", 25)
        val edits = listOf(
            RangeEdit(22, 25, "List", 0),
            RangeEdit(11, 11, "\nimport java.util.List;\n", 0),
        )
        s.applyEdits(edits, TextRange(26 + 24))
        assertEquals("package p;\n\nimport java.util.List;\n\nclass A { List }", s.doc.text)
    }

    // ---- Tab: indent to the next tab stop, Shift-Tab dedents ----

    @Test
    fun indentFillsToNextTabStop() {
        // caret at column 0 → full tab width
        val s0 = session("ab", 0)
        s0.indent()
        assertEquals("    ab", s0.doc.text)
        assertEquals(TextRange(4), s0.selection)

        // caret at column 2 → only 2 spaces to reach column 4
        val s2 = session("ab", 2)
        s2.indent()
        assertEquals("ab  ", s2.doc.text)
        assertEquals(TextRange(4), s2.selection)

        // already on a tab stop (column 4) → a full tab width to the next
        val s4 = session("    x", 4)
        s4.indent()
        assertEquals("        x", s4.doc.text)
    }

    @Test
    fun indentCountsExistingTabsAsTabStops() {
        // a leading '\t' is column 4; caret after it → next stop is column 8 → 4 spaces
        val s = session("\tx", 1)
        s.indent()
        assertEquals("\t    x", s.doc.text)
    }

    @Test
    fun indentMultiLineSelectionIndentsEachNonBlankLine() {
        val text = "a\n\nb"
        val s = session(text, 0)
        s.setSelectionRange(0, text.length)
        s.indent()
        assertEquals("    a\n\n    b", s.doc.text) // blank middle line untouched
        assertEquals(TextRange(0, "    a\n\n    b".length), s.selection)
    }

    @Test
    fun dedentRemovesOneIndentLevel() {
        val s = session("      x", 7) // 6 leading spaces
        s.dedent()
        assertEquals("  x", s.doc.text) // removed up to one tab width (4)
    }

    @Test
    fun dedentRemovesLeadingTab() {
        val s = session("\tx", 2)
        s.dedent()
        assertEquals("x", s.doc.text)
    }

    @Test
    fun dedentMultiLineKeepsBlockSelected() {
        val text = "    a\n    b"
        val s = session(text, 0)
        s.setSelectionRange(0, text.length)
        s.dedent()
        assertEquals("a\nb", s.doc.text)
        assertEquals(TextRange(0, "a\nb".length), s.selection)
    }

    // ---- per-language smart Enter (newline handlers) ----

    /** Insert a newline at the caret and return (text, caretOffset). */
    private fun enter(s: EditorSession): Pair<String, Int> {
        s.commitText("\n")
        return s.doc.text to s.selection.start
    }

    @Test
    fun enterContinuesIndent() {
        val s = session("    foo();", 10, CodeLanguage.Java) // caret at end
        val (text, caret) = enter(s)
        assertEquals("    foo();\n    ", text)
        assertEquals(text.length, caret)
    }

    @Test
    fun enterIndentsDeeperAfterOpenBrace() {
        val s = session("class A {", 9, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("class A {\n    ", text)
    }

    @Test
    fun enterIndentsDeeperAfterBraceWithTrailingSpaceAndComment() {
        // last non-ws (ignoring a trailing line comment) is the brace → still go deeper
        val s = session("class A {  // start", 19, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("class A {  // start\n    ", text)
    }

    @Test
    fun enterExpandsEmptyBracePairWithDedentedClose() {
        // caret between the auto-closed braces: `{|}`
        val s = session("class A {}", 9, CodeLanguage.Java)
        val (text, caret) = enter(s)
        assertEquals("class A {\n    \n}", text)
        assertEquals("class A {\n    ".length, caret) // caret on the middle (deeper) line
    }

    @Test
    fun enterIndentsAfterBracelessIf() {
        val s = session("        if (x)", 14, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("        if (x)\n            ", text) // one level deeper than the 8-space header
    }

    @Test
    fun enterIndentsAfterElseIf() {
        val s = session("    } else if (x)", 17, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("    } else if (x)\n        ", text)
    }

    @Test
    fun enterDoesNotIndentAfterCompletedStatement() {
        val s = session("    foo();", 10, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("    foo();\n    ", text) // same indent, not deeper
    }

    @Test
    fun enterContinuesBlockComment() {
        // inside a Javadoc continuation line → new line gets an aligned `* `
        val s = session("    /**\n     * foo", 18, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("    /**\n     * foo\n     * ", text)
    }

    @Test
    fun enterAutoClosesJavadoc() {
        // caret right after `/**` on an unclosed comment → insert ` * ` line + closing ` */`
        val s = session("    /**", 7, CodeLanguage.Java)
        val (text, caret) = enter(s)
        assertEquals("    /**\n     * \n     */", text)
        assertEquals("    /**\n     * ".length, caret)
    }

    @Test
    fun kotlinEntersDeeperAfterArrow() {
        val s = session("    items.map {", 15, CodeLanguage.Kotlin)
        // after `{` → deeper anyway; check the `->` rule on its own line
        val s2 = session("    1 ->", 8, CodeLanguage.Kotlin)
        val (text, _) = enter(s2)
        assertEquals("    1 ->\n        ", text)
        // sanity: brace still deepens in Kotlin
        val (t1, _) = enter(s)
        assertEquals("    items.map {\n        ", t1)
    }

    @Test
    fun kotlinExpandsLambdaArrowBeforeCloser() {
        // `listOf().filter { it -> |}` → body on a deeper line, `}` dropped to the base indent
        val src = "    listOf().filter { it -> }"
        val caret = src.indexOf("-> ") + 3 // right after "-> ", before the space + "}"
        val s = session(src, caret, CodeLanguage.Kotlin)
        val (text, c) = enter(s)
        assertEquals("    listOf().filter { it ->\n        \n    }", text)
        assertEquals("    listOf().filter { it ->\n        ".length, c) // caret on the deeper body line
    }

    @Test
    fun kotlinExpandsBraceBeforeCloserSwallowingBlanks() {
        // `map {  |  }` → the blanks around the caret are swallowed; `}` lands de-dented
        val s = session("    map {   }", "    map {".length, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("    map {\n        \n    }", text)
    }

    // ---- #4 switch/case indent (Java) ----

    @Test
    fun enterIndentsDeeperAfterCaseLabel() {
        val s = session("    case FOO:", 13, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("    case FOO:\n        ", text)
    }

    @Test
    fun enterIndentsDeeperAfterDefaultLabel() {
        val s = session("    default:", 12, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("    default:\n        ", text)
    }

    @Test
    fun enterDoesNotTreatPlainLabelAsCase() {
        // a ternary or a `foo:` label must not be mistaken for a case label
        val s = session("    int x = a ? b : c;", 22, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("    int x = a ? b : c;\n    ", text) // same indent
    }

    // ---- #2/#3 continuation indent ----

    @Test
    fun enterContinuationIndentsAfterOperator() {
        val s = session("    int x = a +", 15, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("    int x = a +\n        ", text) // one level deeper
    }

    @Test
    fun enterContinuationDoesNotCompound() {
        // second wrap of a continued statement stays at one level (prev line already ends on an operator)
        val s = session("    int x = a +\n        b +", "    int x = a +\n        b +".length, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("    int x = a +\n        b +\n        ", text) // still 8, not 12
    }

    @Test
    fun enterAfterTrailingCommaInArgListKeepsSiblingIndent() {
        // `Column(\n    modifier = Modifier.fillMaxSize(),|` — the line's brackets are balanced, so the comma
        // separates siblings: the next argument lines up with `modifier`, not one level deeper.
        val src = "Column(\n    modifier = Modifier.fillMaxSize(),"
        val s = session(src, src.length, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("$src\n    ", text) // aligned with `modifier`, not 8 spaces
    }

    @Test
    fun enterAfterTrailingCommaWithUnclosedOpenerIndentsDeeper() {
        // `val list = listOf(1, 2, 3,|` — the line leaves `(` unclosed, so its wrapped tail goes one deeper.
        val src = "    val list = listOf(1, 2, 3,"
        val s = session(src, src.length, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("$src\n        ", text) // 8 spaces (one level deeper than the 4-space line)
    }

    @Test
    fun enterAfterSecondArgKeepsSiblingIndent() {
        // a run of comma-separated args all stay at the same indent (no marching right)
        val src = "Column(\n    modifier = foo(),\n    color = bar(),"
        val s = session(src, src.length, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("$src\n    ", text)
    }

    // ---- closer-dedent: Enter before a closing bracket in a multi-line list/call ----

    @Test
    fun enterBeforeCloserInArgListDropsCloserDedented() {
        // `Column(\n    modifier = x,|)` → the `)` falls under `Column`, caret on a body line at the item indent.
        val src = "Column(\n    modifier = x,)"
        val s = session(src, src.length - 1, CodeLanguage.Kotlin) // caret right before the final ')'
        val (text, c) = enter(s)
        assertEquals("Column(\n    modifier = x,\n    \n)", text)
        assertEquals("Column(\n    modifier = x,\n    ".length, c)
    }

    @Test
    fun enterBeforeCloserWithOpenerOnSameLineGoesDeeper() {
        // `listOf(1, 2|)` — opener is on this line, so the base is this line's indent and the body one deeper.
        val src = "    listOf(1, 2)"
        val s = session(src, src.length - 1, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("    listOf(1, 2\n        \n    )", text)
    }

    // ---- indent-style detection (tabs / 2-space) ----

    @Test
    fun enterUsesDetectedTwoSpaceUnit() {
        // a clearly 2-space-indented buffer → Enter after `{` adds 2 spaces, not 4
        val src = "class A {\n  fun f() {\n    g()\n  }\n  val x = {"
        val s = session(src, src.length, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("$src\n    ", text) // line indent 2 + one 2-space level = 4
    }

    @Test
    fun enterUsesDetectedTabUnit() {
        val src = "class A {\n\tfun f() {\n\t\tg()\n\t}\n\tval x = {"
        val s = session(src, src.length, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("$src\n\t\t", text) // line's one tab + one tab level
    }

    // ---- Kotlin raw string / Java text block ----

    @Test
    fun enterInsideRawStringKeepsLineIndent() {
        // inside `"""…"""` the content is literal — keep the line's margin, no continuation/deeper rules
        val src = "val q = \"\"\"\n    SELECT *"
        val s = session(src, src.length, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("$src\n    ", text)
    }

    // ---- annotation / modifier-only lines ----

    @Test
    fun enterAfterAnnotationKeepsSameIndent() {
        val src = "    @Composable"
        val s = session(src, src.length, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("    @Composable\n    ", text)
    }

    @Test
    fun enterAfterAnnotationWithArgsKeepsSameIndent() {
        val src = "    @Preview(showBackground = true)"
        val s = session(src, src.length, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("$src\n    ", text)
    }

    // ---- wrap before a leading operator ----

    @Test
    fun enterBeforeLeadingOperatorIndents() {
        val src = "    val x = a + b"
        val s = session(src, src.indexOf("+ b"), CodeLanguage.Kotlin) // caret right before '+'
        val (text, _) = enter(s)
        assertEquals("    val x = a\n        + b", text) // tail one level deeper, trailing space swallowed
    }

    @Test
    fun enterBeforeDotInChainIndents() {
        val src = "    builder.append(x).append(y)"
        val s = session(src, src.lastIndexOf(".append"), CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("    builder.append(x)\n        .append(y)", text)
    }

    @Test
    fun enterBeforeDotMidChainDoesNotCompound() {
        // the current line already begins with `.` → it's mid-chain, so the next wrap stays at the same level
        val src = "    a\n        .b().c()"
        val s = session(src, src.indexOf(".c()"), CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("    a\n        .b()\n        .c()", text)
    }

    // ---- typed closing bracket dedents to its opener ----

    @Test
    fun typingClosingBraceDedentsToOpener() {
        val src = "fun f() {\n    g()\n    " // caret on the blank indented line
        val s = session(src, src.length, CodeLanguage.Kotlin)
        s.commitText("}")
        assertEquals("fun f() {\n    g()\n}", s.doc.text)
    }

    @Test
    fun typingClosingParenOnBlankLineDedents() {
        val src = "foo(\n    a,\n    " // caret on the blank line
        val s = session(src, src.length, CodeLanguage.Kotlin)
        s.commitText(")")
        assertEquals("foo(\n    a,\n)", s.doc.text)
    }

    @Test
    fun typingClosingBraceMidLineDoesNotReindent() {
        // not the first thing on its line → plain insert, no reindent
        val src = "val m = mapOf(a to b"
        val s = session(src, src.length, CodeLanguage.Kotlin)
        s.commitText("}")
        assertEquals("val m = mapOf(a to b}", s.doc.text)
    }

    // ---- Smart Enter (Complete Statement) — the pure smartEnter() edit ----

    private fun applySmart(src: String, caret: Int, lang: CodeLanguage): Pair<String, Int> {
        val s = session(src, caret, lang)
        val e = smartEnter(s.doc.chars, s.selection.start, lang)
        s.applyEdits(listOf(e), TextRange(e.caret))
        return s.doc.text to s.selection.start
    }

    @Test
    fun smartEnterCompletesJavaStatementWithSemicolon() {
        val (text, caret) = applySmart("    foo()", 4, CodeLanguage.Java) // caret mid-line
        assertEquals("    foo();\n    ", text)
        assertEquals(text.length, caret)
    }

    @Test
    fun smartEnterCompletesControlFlowWithBraces() {
        val (text, caret) = applySmart("    if (x)", 6, CodeLanguage.Kotlin)
        assertEquals("    if (x) {\n        \n    }", text)
        assertEquals("    if (x) {\n        ".length, caret) // caret inside the block
    }

    @Test
    fun smartEnterDoesNotDoubleSemicolon() {
        val (text, _) = applySmart("    foo();", 4, CodeLanguage.Java)
        assertEquals("    foo();\n    ", text)
    }

    @Test
    fun smartEnterKotlinAddsNoSemicolon() {
        val (text, _) = applySmart("    foo()", 4, CodeLanguage.Kotlin)
        assertEquals("    foo()\n    ", text)
    }

    // ---- Markdown / line-comment list continuation ----

    @Test
    fun enterContinuesMarkdownBullet() {
        val (text, _) = enter(session("- first", 7, CodeLanguage.Plain))
        assertEquals("- first\n- ", text)
    }

    @Test
    fun enterIncrementsOrderedListMarker() {
        val (text, _) = enter(session("1. one", 6, CodeLanguage.Plain))
        assertEquals("1. one\n2. ", text)
    }

    @Test
    fun enterOnEmptyBulletEndsList() {
        val (text, _) = enter(session("- first\n- ", 10, CodeLanguage.Plain))
        assertEquals("- first\n\n", text)
    }

    @Test
    fun enterContinuesBulletInLineComment() {
        val (text, _) = enter(session("    // - item", 13, CodeLanguage.Kotlin))
        assertEquals("    // - item\n    // - ", text)
    }

    // ---- smart backspace: collapse blank lines above a content line ----

    @Test
    fun backspaceCollapsesBlankLineAboveContent() {
        // `Column(\n\n|) {` → the empty line is removed and `) {` lines up with `Column(`
        val src = "Column(\n\n) {\n\n}"
        val s = session(src, src.indexOf(") {"), CodeLanguage.Kotlin)
        s.backspace()
        assertEquals("Column(\n) {\n\n}", s.doc.text)
        assertEquals("Column(\n".length, s.selection.start)
    }

    @Test
    fun backspaceCollapsesMultipleBlankLinesInOnePress() {
        val src = "foo(\n\n\n\n)"
        val s = session(src, src.lastIndexOf(")"), CodeLanguage.Kotlin)
        s.backspace()
        assertEquals("foo(\n)", s.doc.text)
    }

    @Test
    fun backspaceCollapsesBlankLinesIndentsDeeperAfterOpener() {
        // the previous non-blank line ends with an opener `(`, so the content line lands one level deeper
        val src = "    foo(\n\n        bar)"
        val s = session(src, src.indexOf("bar)"), CodeLanguage.Kotlin)
        s.backspace()
        assertEquals("    foo(\n        bar)", s.doc.text)
    }

    @Test
    fun backspaceCollapsesBlankLinesIndentsDeeperAfterBrace() {
        // the reported case: a blank line under `Column {`, caret in the next line's (mis-)indent → one
        // level deeper than the brace, not aligned with it
        val src = "    Column {\n\n       Text(\"Title\")\n        Text(\"Body\")\n    }"
        val s = session(src, src.indexOf("Text(\"Title\")"), CodeLanguage.Kotlin)
        s.backspace()
        assertEquals("    Column {\n        Text(\"Title\")\n        Text(\"Body\")\n    }", s.doc.text)
    }

    @Test
    fun backspaceCollapsesBlankLinesKeepsCloserAtOpenerIndent() {
        // a content line that is itself the matching closer stays aligned with the opener (not deeper)
        val src = "    foo(\n\n    )"
        val s = session(src, src.indexOf(")"), CodeLanguage.Kotlin)
        s.backspace()
        assertEquals("    foo(\n    )", s.doc.text)
    }

    @Test
    fun backspaceCollapsesBlankLinesDeeperAfterBracelessControlFlow() {
        val src = "    if (x)\n\n        doThing();"
        val s = session(src, src.indexOf("doThing"), CodeLanguage.Java)
        s.backspace()
        assertEquals("    if (x)\n        doThing();", s.doc.text)
    }

    @Test
    fun backspaceCollapsesBlankLinesDeeperAfterXmlStartTag() {
        val src = "<LinearLayout>\n\n    <TextView/>\n</LinearLayout>"
        val s = session(src, src.indexOf("<TextView/>"), CodeLanguage.Xml)
        s.backspace()
        assertEquals("<LinearLayout>\n    <TextView/>\n</LinearLayout>", s.doc.text)
    }

    @Test
    fun backspaceCollapsesBlankLinesNoDeeperAfterPlainLine() {
        // a previous non-blank line that does not open a block → just match its indent
        val src = "    foo()\n\n    bar()"
        val s = session(src, src.indexOf("bar()"), CodeLanguage.Kotlin)
        s.backspace()
        assertEquals("    foo()\n    bar()", s.doc.text)
    }

    @Test
    fun backspaceAtLineStartWithNoBlankAboveJoinsNormally() {
        val src = "foo\nbar"
        val s = session(src, src.indexOf("bar"), CodeLanguage.Kotlin)
        s.backspace()
        assertEquals("foobar", s.doc.text)
    }

    @Test
    fun kotlinChainContinuationIndents() {
        val s = session("    something", 13, CodeLanguage.Kotlin)
        // a trailing `.` wraps the chain one level deeper
        val s2 = session("    builder.append(x).", "    builder.append(x).".length, CodeLanguage.Kotlin)
        val (text, _) = enter(s2)
        assertEquals("    builder.append(x).\n        ", text)
        // a plain identifier line just continues the indent
        val (t1, _) = enter(s)
        assertEquals("    something\n    ", t1)
    }

    // ---- #6 Java string-literal split ----

    @Test
    fun enterSplitsJavaStringWithConcatenation() {
        val src = "    String s = \"foobar\";"
        val caret = src.indexOf("foo") + 3 // between "foo" and "bar"
        val s = session(src, caret, CodeLanguage.Java)
        val (text, c) = enter(s)
        assertEquals("    String s = \"foo\" +\n        \"bar\";", text)
        assertEquals("    String s = \"foo\" +\n        \"".length, c) // caret before "bar"
    }

    @Test
    fun enterDoesNotSplitOutsideString() {
        val src = "    String s = \"x\";" // caret after the literal, before ;
        val caret = src.indexOf(";")
        val s = session(src, caret, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("    String s = \"x\";".substring(0, caret) + "\n    " + ";", text)
    }

    @Test
    fun kotlinDoesNotSplitStrings() {
        // Kotlin string-split is off (templates) → a normal newline inside the string
        val src = "    val s = \"foobar\""
        val caret = src.indexOf("foo") + 3
        val s = session(src, caret, CodeLanguage.Kotlin)
        val (text, _) = enter(s)
        assertEquals("    val s = \"foo\n    bar\"", text)
    }

    // ---- #7 Javadoc/KDoc tag alignment ----

    @Test
    fun enterAlignsJavadocParamDescription() {
        // wrapping a @param line aligns the continuation under the description ("a") — the `*` stays put
        val src = "    /**\n     * @param name a desc"
        val s = session(src, src.length, CodeLanguage.Java)
        val (text, _) = enter(s)
        val descCol = "     * @param name ".length // column where "a" starts (19)
        val cont = "     *" + " ".repeat(descCol - "     *".length) // star at col 5, padded to descCol
        assertEquals("$src\n$cont", text)
    }

    @Test
    fun enterAlignsJavadocReturnDescription() {
        val src = "    /**\n     * @return the value"
        val s = session(src, src.length, CodeLanguage.Java)
        val (text, _) = enter(s)
        val descCol = "     * @return ".length // "the" column (15)
        val cont = "     *" + " ".repeat(descCol - "     *".length)
        assertEquals("$src\n$cont", text)
    }

    @Test
    fun enterContinuesPlainJavadocLineWithSingleSpace() {
        // a non-tag doc line keeps the usual `* ` (regression for the unified alignment path)
        val src = "    /**\n     * hello"
        val s = session(src, src.length, CodeLanguage.Java)
        val (text, _) = enter(s)
        assertEquals("$src\n     * ", text)
    }
}
