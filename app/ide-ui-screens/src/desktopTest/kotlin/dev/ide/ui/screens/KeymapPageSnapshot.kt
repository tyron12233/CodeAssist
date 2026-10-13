package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.UiSettingControl
import dev.ide.ui.backend.UiSettingsPage
import dev.ide.ui.ext.APP_KEY_DEFAULTS
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the Keymap page off-screen from the real app defaults, at a desktop window and a phone, with one
 * shortcut rebound, one cleared and one conflict, so the keycaps, the modified marker and the conflict line can
 * be eyeballed and the page is guarded against a runtime layout error.
 */
class KeymapPageSnapshot {

    private val page = UiSettingsPage(
        id = "keymap",
        title = "Keymap",
        iconId = "keyboard",
        scope = "application",
        controls = APP_KEY_DEFAULTS.mapIndexed { i, d ->
            val default = d.specs.firstOrNull().orEmpty()
            UiSettingControl.Shortcut(
                key = "keymap.${d.actionId}",
                title = d.title,
                description = if (i == 3) "Also bound to Reformat code." else null,
                value = when (i) {
                    1 -> "primary+alt+R"
                    2 -> ""
                    else -> default
                },
                defaultValue = default,
                group = d.group,
            )
        },
    )

    @Test
    fun desktop() {
        render("keymap-desktop.png", 2560, 1600)
    }

    @Test
    fun phone() {
        render("keymap-phone.png", 822, 1780)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, width: Int, height: Int) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    KeymapPageContent(page, mutableStateMapOf(), onSet = { _, _, _ -> }, onStructuralChange = {})
                }
            }
        }
        try {
            scene.render(); scene.render(50_000_000L)
            val png = scene.render(300_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "build/snapshots"
    }
}
