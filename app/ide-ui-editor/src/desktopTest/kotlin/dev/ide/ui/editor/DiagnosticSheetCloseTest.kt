package dev.ide.ui.editor

import androidx.compose.ui.text.TextRange
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.core.EditorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertTrue

/** The problem sheet a chip opens closes on the next edit, and only then. */
class DiagnosticSheetCloseTest {

    private val error = UiDiagnostic(UiSeverity.Error, 1, 9, "Unresolved reference: foo", 8, 11)

    private fun open(): Pair<EditorSession, EditorActionsController> {
        val session = EditorSession("val x = foo", CodeLanguage.Kotlin, TextRange(11))
        val acts = EditorActionsController(session, StubBackend(), "/p/A.kt", CoroutineScope(Dispatchers.Unconfined), {})
        acts.openSheet(error)
        return session to acts
    }

    @Test
    fun typingClosesTheSheet() {
        val (session, acts) = open()
        session.commitText("x")
        acts.closeSheetIfEdited()
        assertTrue(acts.sheetGroup.isEmpty(), "an edit leaves the sheet's message and fixes stale, so it closes")
    }

    @Test
    fun theSheetStaysOpenUntilTheTextChanges() {
        val (session, acts) = open()
        acts.closeSheetIfEdited()
        session.setCaret(0)
        acts.closeSheetIfEdited()
        assertTrue(acts.sheetGroup.isNotEmpty(), "opening it, or moving the caret, is no reason to close it")
    }

    @Test
    fun aSheetReopenedAfterTypingStaysOpen() {
        val (session, acts) = open()
        session.commitText("x")
        acts.openSheet(error)
        acts.closeSheetIfEdited()
        assertTrue(acts.sheetGroup.isNotEmpty(), "reopened after the edit, it describes the current text")
    }
}
