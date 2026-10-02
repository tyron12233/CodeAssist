package dev.ide.ui.editor.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.ide.ui.ext.PreviewSnapshots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PreviewCaptureTest {
    @Test
    fun aComposedPaneCanBeCapturedAndOnlyForItsOwnFile() {
        val scene = ImageComposeScene(width = 200, height = 100, density = Density(1f)) {
            Box(Modifier.fillMaxSize().then(rememberPreviewCapture("/project/app/Main.kt", "GreetingPreview")).background(Color.Red))
        }
        try {
            scene.render()
            assertTrue(PreviewSnapshots.available)
            val shot = runBlocking {
                val pending = async(Dispatchers.Default) { PreviewSnapshots.capture("app/Main.kt") }
                withTimeout(10_000) {
                    while (!pending.isCompleted) scene.render()
                }
                pending.await()
            }
            assertNotNull(shot)
            assertEquals("GreetingPreview", shot.label)
            assertEquals(200, shot.width)
            assertEquals(100, shot.height)
            val decoded = Image.makeFromEncoded(shot.png)
            val pixels = org.jetbrains.skia.Bitmap.makeFromImage(decoded)
            val argb = pixels.getColor(100, 50)
            assertEquals(0xFFFF0000.toInt(), argb, "expected the red the pane drew, got ${Integer.toHexString(argb)}")

            val other = runBlocking { PreviewSnapshots.capture("app/Other.kt") }
            assertNull(other)
        } finally {
            scene.close()
        }
        assertTrue(!PreviewSnapshots.available, "the pane unregisters when it leaves composition")
    }
}
