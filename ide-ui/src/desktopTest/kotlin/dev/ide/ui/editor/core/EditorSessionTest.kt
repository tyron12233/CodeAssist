package dev.ide.ui.editor.core

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.CodeLanguage
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
