package dev.ide.ui.screens

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.colors.BuiltInColorSchemes
import dev.ide.ui.theme.colors.ColorSchemeStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The color scheme editor, rendered off-screen for every preset in both variants.
 *
 * A picture is the only check worth having here: the screen's whole job is to show colors, and every part
 * of it — the staged preview's caret, selection, squiggles and gutter, the per-row swatches drawn in the
 * real ink and font style — is correct or not by eye. The assertion is coarse (a frame that is not blank),
 * so what this really buys is the PNGs; rendering all ten combinations also catches the cheap disasters,
 * like a preset whose text and background land on the same color.
 */
class ColorSchemeSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun everyPresetRenders() {
        for (preset in BuiltInColorSchemes.all) {
            for (dark in listOf(true, false)) {
                val prefs = HashMap<String, String>()
                prefs["colorScheme.active"] = preset.id
                val store = ColorSchemeStore({ prefs[it] }, { key, value -> prefs[key] = value })
                // A real phone viewport (411 x 890 dp), so what the PNG shows is what is above the fold.
                val scene = ImageComposeScene(width = 822, height = 1780, density = Density(2f)) {
                    CodeAssistTheme(dark = dark, editorColorScheme = preset) {
                        ColorSchemeScreen(store = store, isDark = dark, onApply = {}, onBack = {})
                    }
                }
                scene.render() // fonts can still be the fallback on the first frame
                val png = scene.render(FRAME_NS).encodeToData(EncodedImageFormat.PNG)!!.bytes
                val name = "colors-${preset.id}-${if (dark) "dark" else "light"}.png"
                File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
                assertTrue(png.size > 20_000, "$name should render more than a blank frame")
                scene.close()
            }
        }
    }

    /**
     * The attribute list on a tall canvas: it is generated from the registry, so this is where a group
     * that renders as an empty card or a swatch that draws nothing would show up.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theGeneratedAttributeListRenders() {
        val prefs = HashMap<String, String>()
        val scene = ImageComposeScene(width = 822, height = 5200, density = Density(2f)) {
            CodeAssistTheme(dark = true, editorColorScheme = BuiltInColorSchemes.SOLARIZED) {
                ColorSchemeScreen(
                    store = ColorSchemeStore({ prefs[it] }, { key, value -> prefs[key] = value }),
                    isDark = true,
                    onApply = {},
                    onBack = {},
                )
            }
        }
        scene.render()
        val png = scene.render(FRAME_NS).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File("$OUT_DIR/colors-attributes.png").apply { parentFile?.mkdirs() }.writeBytes(png)
        assertTrue(png.size > 60_000, "the attribute list should render more than a blank frame")
        scene.close()
    }

    private companion object {
        const val FRAME_NS = 16_000_000L
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
