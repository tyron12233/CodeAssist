package dev.ide.ui.screens

import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiVersionNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WhatsNewTest {

    private val item = UiStoreItem(
        id = "studymate", kind = UiStoreItemKind.Community, title = "StudyMate", summary = "s", category = "android",
        version = "2.3.3", changelog = "Added Economics.",
    )
    private val history = listOf(
        UiVersionNote("2.3.3", 2003003, "Added Economics."),
        UiVersionNote("2.3.2", 2003002, null),
        UiVersionNote("2.0.3", 2000003, "Fully developed."),
        UiVersionNote("2.0.2", 2000002, "More lessons."),
    )

    @Test
    fun withAnOlderCopyInstalledItListsEveryVersionSinceThatOne() {
        val w = whatsNew(item, history, installedVersion = "2.0.2")!!
        assertEquals("2.0.2", w.sinceVersion)
        assertEquals(listOf("2.3.3", "2.3.2", "2.0.3"), w.notes.map { it.version })
    }

    @Test
    fun withoutACopyItIsTheLiveVersionsNotes() {
        val w = whatsNew(item, history, installedVersion = null)!!
        assertNull(w.sinceVersion)
        assertEquals(listOf("2.3.3"), w.notes.map { it.version })
    }

    @Test
    fun anInstalledVersionTheHistoryDoesNotKnowFallsBackToTheLiveNotes() {
        // History that failed to load, or a copy from before the listing kept its versions.
        assertEquals(listOf("2.3.3"), whatsNew(item, emptyList(), installedVersion = "1.0.0")!!.notes.map { it.version })
        assertNull(whatsNew(item, history, installedVersion = "0.9.0")!!.sinceVersion)
    }

    @Test
    fun noNotesAtAllIsNoCard() {
        assertNull(whatsNew(item.copy(changelog = null), emptyList(), null))
        // Versions since the installed one that all shipped without notes say nothing worth a card.
        val silent = listOf(UiVersionNote("2.3.3", 2003003, null), UiVersionNote("2.3.2", 2003002, "Old notes."))
        assertNull(whatsNew(item.copy(changelog = null), silent, installedVersion = "2.3.2"))
        assertNull(whatsNew(item.copy(version = null), history, null), "a bundled item has no versions")
    }

    @Test
    fun aPreviewIsTheFirstLinesWithoutTheirListMarkers() {
        assertEquals(
            "Add more lessons  ·  Added CBT practice  ·  Built-in calculator",
            changelogPreview("1.Add more lessons\n2. Added CBT practice\n\n- Built-in calculator\n* Fourth"),
        )
        assertNull(changelogPreview("  \n "))
        assertNull(changelogPreview(null))
    }
}
