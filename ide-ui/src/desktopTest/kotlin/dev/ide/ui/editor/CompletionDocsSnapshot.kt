package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Off-screen PNGs of the completion popup wide (docs beside) vs narrow (docs flip → the selected row shows an
 *  ⓘ instead of a squished side panel). Not assertions — visual snapshots. */
class CompletionDocsSnapshot {
    private fun item(label: String, detail: String, container: String, kind: UiCompletionKind, doc: String? = null) =
        UiCompletionItem(label, label, detail, container, doc, kind, 0)

    private val items = listOf(
        item("append", "(s: String): StringBuilder", "StringBuilder", UiCompletionKind.Method,
            "Appends the string representation of the argument to this sequence, then returns this builder so calls can be chained. This is a deliberately long doc so the side panel would clearly overflow a phone width."),
        item("apply", "{ }: T", "kotlin", UiCompletionKind.Method, "Calls the block with this value as its receiver and returns this value."),
        item("asList", "(): List<T>", "kotlin.collections", UiCompletionKind.Method),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderWideVsNarrow() {
        // Scene dims are PIXELS; at density 2 a 440dp list + 320dp doc ≈ 1532px, a 300dp popup ≈ 600px.
        // Wide: list + doc panel beside (current behavior).
        snapshot("completion-wide.png", 1600, 360) {
            Box(Modifier.fillMaxSize().background(Ca.colors.editorBg).padding(8.dp)) {
                CompletionList(items, selectedIndex = 0, prefix = "app", width = 440.dp, onPick = {}, onHover = {}, docsBeside = true)
            }
        }
        // Narrow: just the list at phone width; the selected row shows the ⓘ (tap → docs, can't snapshot the tap).
        snapshot("completion-narrow.png", 720, 420) {
            Box(Modifier.fillMaxSize().background(Ca.colors.editorBg).padding(8.dp)) {
                CompletionList(items, selectedIndex = 0, prefix = "app", width = 300.dp, onPick = {}, onHover = {}, docsBeside = false)
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content() }
        }
        try {
            scene.render(); val img = scene.render(16_000_000L)
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    private companion object {
        const val OUT_DIR = "/private/tmp/claude-501/-Users-tyronscott-JavaProjects-CodeAssist/8b7a0e57-9bee-4671-94e9-9bfc2f97e5ba/scratchpad"
    }
}
