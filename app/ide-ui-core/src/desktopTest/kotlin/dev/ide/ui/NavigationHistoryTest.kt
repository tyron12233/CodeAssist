package dev.ide.ui

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Navigate Back / Forward: the history itself, and the jumps [IdeUiState] records into it. */
class NavigationHistoryTest {

    private fun place(path: String, line: Int) = NavPlace(path, line * 10, line)

    @Test
    fun backThenForwardRetracesTheJumps() {
        val h = NavigationHistory()
        h.record(place("A", 1)) // A:1 -> B:5
        h.record(place("B", 5)) // B:5 -> C:9
        assertEquals(place("B", 5), h.back(place("C", 9)))
        assertEquals(place("A", 1), h.back(place("B", 5)))
        assertNull(h.back(place("A", 1)))
        assertEquals(place("B", 5), h.forward(place("A", 1)))
        assertEquals(place("C", 9), h.forward(place("B", 5)))
        assertFalse(h.canGoForward)
    }

    @Test
    fun aNewJumpDropsTheForwardList() {
        val h = NavigationHistory()
        h.record(place("A", 1))
        h.back(place("B", 5))
        assertTrue(h.canGoForward)
        h.record(place("A", 1))
        assertFalse(h.canGoForward, "a browser forgets forward once you go somewhere new")
    }

    @Test
    fun twoRecordsOfOneLineAreOnePlace() {
        val h = NavigationHistory()
        h.record(NavPlace("A", 10, 1))
        h.record(NavPlace("A", 14, 1)) // a go-to then the tab switch it caused, from the same spot
        assertEquals(1, generateSequence { h.back(null) }.count())
    }

    @Test
    fun backSkipsAnEntryThatIsWhereTheCaretAlreadyIs() {
        val h = NavigationHistory()
        h.record(place("A", 1))
        h.record(place("B", 5))
        assertEquals(place("A", 1), h.back(place("B", 5)), "B:5 is here already, so Back goes on to A")
    }

    @Test
    fun aDeletedFileLeavesTheHistoryAndARenamedOneIsFollowed() {
        val h = NavigationHistory()
        h.record(place("/p/Gone.kt", 1))
        h.record(place("/p/old/Kept.kt", 2))
        h.forget { it == "/p/Gone.kt" }
        h.repath { if (it.startsWith("/p/old/")) "/p/new/" + it.removePrefix("/p/old/") else null }
        assertEquals("/p/new/Kept.kt", h.back(null)?.path)
        assertNull(h.back(null))
    }

    // ---- the jumps IdeUiState records ----

    private val disk = mapOf(
        "/p/A.kt" to "fun a() {\n    b()\n}\n",
        "/p/B.kt" to "fun b() {}\n\n\nfun c() {}\n",
    )

    private fun state(): IdeUiState {
        val backend = object : StubBackend() {
            override fun readFile(path: String) = disk[path] ?: ""
        }
        return IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
    }

    @Test
    fun backReturnsFromAGoToDeclarationIntoAnotherFileAndForwardRedoesIt() {
        val state = state()
        runBlocking { state.openSuspend("/p/A.kt", "A.kt") }
        val call = disk.getValue("/p/A.kt").indexOf("b()")
        state.active!!.session.setCaret(call)

        state.openAt("/p/B.kt", disk.getValue("/p/B.kt").indexOf("b()")) // Go to Declaration
        assertEquals("/p/B.kt", state.active?.path)
        assertTrue(state.navHistory.canGoBack)

        assertTrue(state.navigateBack())
        assertEquals("/p/A.kt", state.active?.path)
        assertEquals(TextRange(call), state.active?.session?.selection, "the caret is back on the call")

        assertTrue(state.navigateForward())
        assertEquals("/p/B.kt", state.active?.path)
        assertFalse(state.navigateForward())
    }

    @Test
    fun aJumpWithinTheFileAndATabClickAreBothPlacesToReturnTo() {
        val state = state()
        runBlocking {
            state.openSuspend("/p/A.kt", "A.kt")
            state.openSuspend("/p/B.kt", "B.kt")
        }
        val b = disk.getValue("/p/B.kt")
        state.openAt("/p/B.kt", b.indexOf("fun c")) // same file, another line (e.g. the structure view)
        state.selectTab(0) // over to A.kt

        assertTrue(state.navigateBack())
        assertEquals("/p/B.kt", state.active?.path)
        assertEquals(TextRange(b.indexOf("fun c")), state.active?.session?.selection)
        assertTrue(state.navigateBack())
        assertEquals(TextRange(0), state.active?.session?.selection, "before the in-file jump: the top of B.kt")
    }
}
