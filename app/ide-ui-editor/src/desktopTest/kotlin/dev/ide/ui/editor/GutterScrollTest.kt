package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The line-number gutter stays pinned while the code scrolls sideways, unless `scrollGutter` (Settings → Editor)
 * lets it scroll off with the code. Renders the real [CodeEditor] off screen, swipes it sideways, and compares
 * the strip of pixels where the gutter sits before and after.
 */
class GutterScrollTest {

    private val longLine = buildString {
        appendLine("fun greet(name: String) {")
        appendLine("    val message = \"Hello, \" + name + \"! This is a deliberately very long line of source code that runs well past the right edge of a narrow viewport by a wide margin indeed.\"")
        appendLine("    println(message)")
        appendLine("}")
    }

    @Test
    fun `a pinned gutter stays put while the code scrolls`() {
        val (before, after, scrolled) = swipe(scrollGutter = false, name = "gutter-pinned")
        assertTrue(scrolled > GUTTER_STRIP_PX, "the swipe must scroll further than the gutter is wide (was $scrolled)")
        assertEquals(0, differingPixels(before, after), "a pinned gutter must look the same after a sideways scroll")
    }

    @Test
    fun `a scrolling gutter leaves with the code and comes back`() {
        val (before, after, scrolled) = swipe(scrollGutter = true, name = "gutter-scrolling")
        assertTrue(scrolled > GUTTER_STRIP_PX, "the swipe must scroll further than the gutter is wide (was $scrolled)")
        assertTrue(
            differingPixels(before, after) > 0,
            "with the gutter scrolling, the line numbers must be gone once the code has scrolled past them",
        )
        // At rest the two modes are the same picture: the setting only matters once the code has moved.
        val pinnedBefore = swipe(scrollGutter = false, name = "gutter-pinned").first
        assertEquals(0, differingPixels(before, pinnedBefore, wholeFrame = true), "unscrolled, both modes must render alike")
    }

    @Test
    fun `a tap where the scrolled-off gutter was lands in the code`() {
        val session = EditorSession(longLine, languageFor("Sample.kt"), TextRange(0))
        swipe(scrollGutter = true, name = "gutter-tap", session = session, tapAfter = Offset(10f, 60f))
        assertTrue(
            session.selection.start > session.doc.lineStart(1),
            "the tap at the left edge should place the caret inside the scrolled long line (was ${session.selection})",
        )
    }

    /** Renders, swipes left, renders again; returns both frames and how far the document scrolled. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun swipe(
        scrollGutter: Boolean,
        name: String,
        session: EditorSession = EditorSession(longLine, languageFor("Sample.kt"), TextRange(0)),
        tapAfter: Offset? = null,
    ): Triple<Image, Image, Float> {
        val scene = ImageComposeScene(width = 760, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.width(360.dp).fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("Sample.kt", remember { session }, PreviewBackend, Modifier.fillMaxSize(), scrollGutter = scrollGutter)
                }
            }
        }
        try {
            var frame = 0L
            fun pump(millis: Long): Image {
                var slept = 0L
                var last = scene.render(frame)
                while (slept < millis) {
                    Thread.sleep(FRAME_MS)
                    slept += FRAME_MS
                    frame += FRAME_NS
                    last = scene.render(frame)
                }
                return last
            }
            val before = pump(4 * FRAME_MS)
            var x = 600f
            val y = 60f // inside the long second line
            var stamp = 0L
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Touch, timeMillis = stamp)
            repeat(10) {
                stamp += FRAME_MS
                x -= 40f
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = PointerType.Touch, timeMillis = stamp)
                pump(FRAME_MS)
            }
            // Hold still before lifting so the release carries no fling.
            repeat(4) {
                stamp += FRAME_MS
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), type = PointerType.Touch, timeMillis = stamp)
                pump(FRAME_MS)
            }
            stamp += FRAME_MS
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Touch, timeMillis = stamp)
            if (tapAfter != null) {
                pump(600)
                stamp += 600
                scene.sendPointerEvent(PointerEventType.Press, tapAfter, type = PointerType.Touch, timeMillis = stamp)
                stamp += FRAME_MS
                scene.sendPointerEvent(PointerEventType.Release, tapAfter, type = PointerType.Touch, timeMillis = stamp)
            }
            // Long enough for the scrollbar thumb's fade and any settle to finish.
            val after = pump(2000)
            save(before, "$name-before.png")
            save(after, "$name-after.png")
            return Triple(before, after, session.hScrollOffsetPx)
        } finally {
            scene.close()
        }
    }

    /** Pixels that differ in the gutter strip (or the whole frame), above the horizontal scrollbar. */
    private fun differingPixels(a: Image, b: Image, wholeFrame: Boolean = false): Int {
        val pa = Bitmap.makeFromImage(a)
        val pb = Bitmap.makeFromImage(b)
        val right = if (wholeFrame) a.width else GUTTER_STRIP_PX
        var n = 0
        for (y in 0 until a.height - SCROLLBAR_BAND_PX) for (x in 0 until right) {
            if (pa.getColor(x, y) != pb.getColor(x, y)) n++
        }
        return n
    }

    private fun save(image: Image, name: String) {
        val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: return
        File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
    }

    private companion object {
        const val FRAME_MS = 50L
        const val FRAME_NS = 16_000_000L
        /** Inside the gutter of a four-line file at density 2, short of its border. */
        const val GUTTER_STRIP_PX = 44
        /** The bottom band the horizontal scrollbar lives in, which moves with any scroll. */
        const val SCROLLBAR_BAND_PX = 40
    }
}
