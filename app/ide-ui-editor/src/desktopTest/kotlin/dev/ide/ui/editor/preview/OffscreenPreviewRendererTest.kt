package dev.ide.ui.editor.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiComposePreview
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The agent's headless preview: a file nobody has open is rendered by composing its preview pane off screen,
 * waiting for it to settle, and capturing the frame the pane itself would show.
 */
class OffscreenPreviewRendererTest {

    private val text = "@Preview @Composable fun RedCard() {}\n@Preview @Composable fun BlueCard() {}\n"

    private val backend = object : StubBackend() {
        override suspend fun composePreviews(path: String, text: String): List<UiComposePreview> = listOf(
            UiComposePreview("RedCard", offset = 0),
            UiComposePreview("BlueCard", offset = 40),
        )
    }

    /** Draws a solid square in the colour the preview's name says, and reports itself settled. */
    private val host = object : ComposePreviewHost {
        @Composable
        override fun Preview(
            path: String,
            preview: UiComposePreview,
            text: String,
            dark: Boolean,
            onProblems: (List<PreviewIssue>) -> Unit,
            onBusy: (Boolean) -> Unit,
            modifier: Modifier,
        ) {
            LaunchedEffect(preview.variantId) { onBusy(false) }
            val color = if (preview.functionName == "BlueCard") Color.Blue else Color.Red
            Box(modifier.size(120.dp).background(color))
        }
    }

    private fun renderer() = OffscreenPreviewRenderer(backend, host, ImageSceneComposer(), timeoutMs = 30_000)

    @Test
    @Timeout(60)
    fun aFileNobodyHasOpenRendersToAPng() {
        val result = runBlocking { renderer().render("/p/Cards.kt", text, preview = "BlueCard") }
        val shot = assertNotNull(result.snapshot, result.problems.joinToString())
        assertEquals("BlueCard", shot.label)
        assertTrue(shot.width > 1 && shot.height > 1)
        // The centre of the frame is the composable itself: the named preview, not the file's first.
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(Image.makeFromEncoded(shot.png))
        val centre = Color(bitmap.getColor(shot.width / 2, shot.height / 2))
        assertTrue(centre.blue > 0.8f && centre.red < 0.2f, "expected blue, got $centre")
    }

    @Test
    @Timeout(60)
    fun anUnknownPreviewNameListsTheFilesPreviews() {
        val result = runBlocking { renderer().render("/p/Cards.kt", text, preview = "GreenCard") }
        assertNull(result.snapshot)
        assertTrue(result.problems.single().contains("RedCard, BlueCard"), result.problems.single())
    }

    @Test
    @Timeout(60)
    fun aFileWithoutPreviewsSaysSo() {
        val empty = object : StubBackend() {}
        val result = runBlocking { OffscreenPreviewRenderer(empty, host, ImageSceneComposer()).render("/p/Plain.kt", "fun x() = 1", null) }
        assertNull(result.snapshot)
        assertEquals(listOf("No @Preview function in Plain.kt."), result.problems)
    }
}
