package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.CodeAssistTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The find bar on a 360dp phone at a large font scale, where one row used to leave the query field so narrow
 * that "Find" wrapped a letter per line, and on a wide window, where it stays one row. Both over code, to
 * check the bar is opaque.
 */
class FindReplaceBarSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theBarFitsAPhoneAndAWideWindow() {
        for ((name, widthDp) in listOf("phone" to 360, "wide" to 800)) {
            val scene = ImageComposeScene(width = widthDp * 2, height = 520, density = Density(2f, fontScale = 1.3f)) {
                CodeAssistTheme(dark = false) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        for (replaceMode in listOf(false, true)) {
                            FindReplaceBar(
                                query = "", replace = "", replaceMode = replaceMode,
                                options = FindOptions(), matchCount = 0, currentIndex = 0, regexError = false,
                                onQueryChange = {}, onReplaceChange = {}, onToggleReplaceMode = {}, onOptionsChange = {},
                                onPrev = {}, onNext = {}, onReplaceOne = {}, onReplaceAll = {}, onClose = {},
                            )
                        }
                        Text("public final class ShaderFileManager {", Modifier.padding(8.dp))
                    }
                }
            }
            scene.render()
            val png = scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/snapshots/find-bar-$name.png").apply { parentFile?.mkdirs() }.writeBytes(png)
            assertTrue(png.size > 5_000, "find-bar-$name should render more than a blank frame")
            scene.close()
        }
    }
}
