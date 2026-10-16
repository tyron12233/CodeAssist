package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.IdeUiState
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiAutoDisabledInspection
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor notice for a plugin inspection CodeAssist switched off: what it says and which choice it leads
 * with, the first time and the second, and for several at once. Also writes the strip at a phone width to
 * `build/snapshots` to be looked at.
 */
class AutoDisabledInspectionsNoticeTest {

    private val colorPicker = UiAutoDisabledInspection(
        id = "colorpicker.literals", displayName = "Color literals", pluginName = "ColorPicker",
        slow = true, tookMs = 140, budgetMs = 25, times = 1,
    )

    private class FakeBackend(initial: List<UiAutoDisabledInspection>) : StubBackend() {
        val off = MutableStateFlow(initial)
        val reenabled = mutableListOf<String>()
        override val autoDisabledInspections: StateFlow<List<UiAutoDisabledInspection>> get() = off
        override fun reenableInspection(id: String) {
            reenabled += id
            off.value = off.value.filterNot { it.id == id }
        }
    }

    @Test
    fun aFirstDropSaysWhyAndOffersToTurnItBackOn() {
        val backend = FakeBackend(listOf(colorPicker))
        val notice = assertNotNull(composeNotice(backend, "inspection-off-phone.png"))
        assertTrue(notice.summary.startsWith("Turned off for slowing the editor"), "the reason leads: ${notice.summary}")
        assertTrue("Color literals" in notice.summary && "ColorPicker" in notice.summary, notice.summary)
        assertEquals("Turn back on", notice.actionLabel)
        assertEquals("Settings", notice.secondaryLabel)

        notice.onAction!!()
        assertEquals(listOf("colorpicker.literals"), backend.reenabled)
    }

    @Test
    fun aSecondDropLeadsWithKeepingItOff() {
        val backend = FakeBackend(listOf(colorPicker.copy(times = 2)))
        val state = IdeUiState(backend)
        val notice = assertNotNull(composeNotice(backend, "inspection-off-again-phone.png", state))
        assertTrue(notice.summary.startsWith("Turned off again"), notice.summary)
        assertEquals("Keep it off", notice.actionLabel)
        assertEquals("Turn back on", notice.secondaryLabel)

        notice.onAction!!()
        assertTrue(backend.reenabled.isEmpty(), "keeping it off does not turn it back on")
        assertNull(composeNotice(backend, null, state), "and the notice is gone")
    }

    @Test
    fun aDismissedNoticeComesBackForALaterDrop() {
        val backend = FakeBackend(listOf(colorPicker))
        val state = IdeUiState(backend)
        assertNotNull(composeNotice(backend, null, state)).onDismiss!!()
        assertNull(composeNotice(backend, null, state))

        backend.off.value = listOf(colorPicker.copy(times = 2))
        assertNotNull(composeNotice(backend, null, state), "a new drop is news again")
    }

    @Test
    fun severalBecomeOneCountThatOpensSettings() {
        val failing = UiAutoDisabledInspection(
            id = "lint.extra", displayName = "Extra lint", pluginName = "Lint Pack",
            slow = false, tookMs = 0, budgetMs = 25, times = 1,
        )
        val notice = assertNotNull(composeNotice(FakeBackend(listOf(colorPicker, failing)), "inspection-off-many-phone.png"))
        assertTrue(notice.summary.startsWith("2 plugin inspections"), notice.summary)
        assertEquals("Settings", notice.actionLabel)
    }

    @Test
    fun nothingSwitchedOffMeansNoNotice() {
        assertNull(composeNotice(FakeBackend(emptyList()), null))
    }

    /** Compose the notice (and, given [snapshot], the strip showing it at a phone width) and return it. */
    private fun composeNotice(
        backend: FakeBackend,
        snapshot: String?,
        state: IdeUiState = IdeUiState(backend),
    ): EditorNotice? {
        var notice: EditorNotice? = null
        // 860x400 @ density 2 = 430x200dp, a phone width.
        val scene = ImageComposeScene(width = 860, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    notice = autoDisabledInspectionsNotice(state)
                    Box(Modifier.fillMaxWidth()) { EditorNoticeStrip(listOfNotNull(notice)) }
                }
            }
        }
        try {
            scene.render()
            val img = scene.render(500_000_000L)
            if (snapshot != null) {
                val out = File("build/snapshots/$snapshot").apply { parentFile?.mkdirs() }
                out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("wrote snapshot: ${out.absolutePath}")
            }
        } finally {
            scene.close()
        }
        return notice
    }
}
