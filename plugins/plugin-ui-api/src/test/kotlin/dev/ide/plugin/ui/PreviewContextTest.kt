package dev.ide.plugin.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The preview context is what a plugin author's `@Preview` composes against, so what it answers and the fact
 * that its host operations are inert are both part of the published contract.
 */
class PreviewContextTest {

    @Test
    fun `it answers what the preview gave it`() {
        val ctx = UiContext.preview(projectPath = "/Projects/Demo", activeFilePath = "App.kt")
        assertEquals("/Projects/Demo", ctx.projectPath)
        assertEquals("App.kt", ctx.activeFilePath)
    }

    @Test
    fun `no open file is the default, since that is the state a panel is most often wrong about`() {
        val ctx = UiContext.preview()
        assertNull(ctx.activeFilePath)
        assertEquals("/Projects/Sample", ctx.projectPath)
    }

    @Test
    fun `the host operations do nothing rather than throw`() {
        // A body that navigates on click must still be previewable; the click simply goes nowhere.
        val ctx = ScreenUiContext.preview()
        ctx.openFile("App.kt", offset = 12)
        ctx.openScreen("com.example.screen")
        ctx.back()
    }
}
