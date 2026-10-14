package dev.ide.ui

import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tool windows on sides: each docks on one (its natural side unless moved, remembered across launches), each
 * side shows one window at a time, and zen mode hides what is open and gives it back afterwards.
 */
class ConsolePaneAndZenTest {

    private val prefs = mutableMapOf<String, String>()

    private fun state(): IdeUiState {
        val backend = object : StubBackend() {
            override fun preference(key: String): String? = prefs[key]
            override fun setPreference(key: String, value: String) { prefs[key] = value }
        }
        return IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
    }

    @Test
    fun windowsStartOnTheirNaturalSide() {
        val s = state()
        assertEquals(ToolWindowSide.Left, s.sideOf(LeftPanelId.FILES))
        assertEquals(ToolWindowSide.Bottom, s.sideOf(ToolWindowIds.BUILD))
        assertEquals(ToolWindowSide.Bottom, s.sideOf(ToolWindowIds.RUN))
    }

    @Test
    fun aMovedWindowShowsOnItsNewSideAndStaysThereNextLaunch() {
        val first = state()
        first.moveToolWindow(ToolWindowIds.RUN, ToolWindowSide.Right)
        assertTrue(first.runOpen)
        assertEquals(ToolWindowIds.RUN, first.shownOn(ToolWindowSide.Right))
        first.bottomPaneHeight = 210f
        first.savePaneWidths()

        val next = state()
        assertEquals(ToolWindowSide.Right, next.sideOf(ToolWindowIds.RUN))
        assertEquals(210f, next.bottomPaneHeight)
        // Moving it home drops the override.
        next.moveToolWindow(ToolWindowIds.RUN, ToolWindowSide.Bottom)
        assertEquals(ToolWindowSide.Bottom, state().sideOf(ToolWindowIds.RUN))
    }

    @Test
    fun aSideShowsOneWindowAtATime() {
        val s = state()
        s.moveToolWindow(LeftPanelId.SEARCH, ToolWindowSide.Bottom)
        s.consoleOpen = true
        assertEquals(ToolWindowIds.BUILD, s.shownOn(ToolWindowSide.Bottom))
        s.selectLeftPanel(LeftPanelId.SEARCH)
        assertEquals(LeftPanelId.SEARCH, s.shownOn(ToolWindowSide.Bottom))
        assertFalse(s.consoleOpen)
        // Files still has the left side to itself, so both are open at once.
        s.selectLeftPanel(LeftPanelId.FILES)
        assertEquals(LeftPanelId.FILES, s.shownOn(ToolWindowSide.Left))
        assertEquals(LeftPanelId.SEARCH, s.shownOn(ToolWindowSide.Bottom))
    }

    @Test
    fun aSideSavedByANewerVersionIsIgnored() {
        prefs[IdeUiState.TOOL_WINDOW_SIDES_PREF] = "build=Floating,run=Right"
        val s = state()
        assertEquals(ToolWindowSide.Bottom, s.sideOf(ToolWindowIds.BUILD))
        assertEquals(ToolWindowSide.Right, s.sideOf(ToolWindowIds.RUN))
    }

    @Test
    fun zenModeHidesTheOpenToolWindowsAndGivesThemBack() {
        val s = state()
        s.selectedLeftPanel = LeftPanelId.FILES
        s.showRunOutput()
        s.maximizedSide = ToolWindowSide.Bottom

        s.setZen(true)
        assertTrue(s.zenMode)
        assertNull(s.selectedLeftPanel)
        assertFalse(s.runOpen)
        assertNull(s.maximizedSide)

        s.setZen(false)
        assertFalse(s.zenMode)
        assertEquals(LeftPanelId.FILES, s.selectedLeftPanel)
        assertTrue(s.runOpen)
    }

    @Test
    fun aToolWindowOpenedDuringZenModeStaysAsItIs() {
        val s = state()
        s.selectedLeftPanel = LeftPanelId.FILES
        s.consoleOpen = true
        s.setZen(true)
        // A run started meanwhile opened the Run terminal; leaving zen mode must not swap the build console back.
        s.showRunOutput()
        s.selectedLeftPanel = LeftPanelId.SEARCH

        s.setZen(false)
        assertEquals(LeftPanelId.SEARCH, s.selectedLeftPanel)
        assertTrue(s.runOpen)
        assertFalse(s.consoleOpen)
    }
}
