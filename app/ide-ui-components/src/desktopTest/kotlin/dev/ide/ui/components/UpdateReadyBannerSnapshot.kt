package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** The "update ready" bar over an empty window, light and dark, rendered off screen for reading. */
class UpdateReadyBannerSnapshot {

    @Test
    fun renderDark() {
        snapshot("update-ready-dark.png", dark = true)
    }

    @Test
    fun renderLight() {
        snapshot("update-ready-light.png", dark = false)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, dark: Boolean) {
        val scene = ImageComposeScene(width = 820, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    UpdateReadyBanner(visible = true, onRestart = {}, onLater = {})
                }
            }
        }
        try {
            scene.render()
            val img = scene.render(2_000_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(OUT_DIR, name).apply { parentFile?.mkdirs() }.writeBytes(png)
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT_DIR = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots")
    }
}
