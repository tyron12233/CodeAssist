package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Off-screen PNG of the keyboard symbol bar so its layout (Tab key on the left + scrolling symbols) can be
 *  eyeballed without launching the app. Not an assertion. */
class SymbolBarSnapshot {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderSymbolBar() {
        snapshot("symbol-bar.png", 760, 96) {
            Box(Modifier.fillMaxSize().background(Ca.colors.editorBg)) {
                EditorSymbolBar(onTab = {}, onSymbol = {}, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content() }
        }
        try {
            scene.render()
            val img = scene.render(16_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = "$OUT_DIR/$name"
            File(out).apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $out (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "/private/tmp/claude-501/-Users-tyronscott-JavaProjects-CodeAssist/8b7a0e57-9bee-4671-94e9-9bfc2f97e5ba/scratchpad"
    }
}
