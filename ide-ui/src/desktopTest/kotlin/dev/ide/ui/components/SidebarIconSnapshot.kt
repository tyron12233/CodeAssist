package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Off-screen PNG of the sidebar toggle at drawer fractions 0 → 1 so the miniature-screen morph (divider
 *  slide + accent fill) can be eyeballed. Not an assertion. */
class SidebarIconSnapshot {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderFractionSweep() {
        val scene = ImageComposeScene(width = 520, height = 120, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Row(
                    Modifier.fillMaxSize().background(Ca.colors.bg).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (f in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                        SidebarToggleButton(fraction = { f }, onClick = {})
                    }
                }
            }
        }
        try {
            val img = scene.render(16_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT/sidebar-icon-sweep.png").writeBytes(png)
            println("wrote $OUT/sidebar-icon-sweep.png")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT = "/private/tmp/claude-501/-Users-tyronscott-JavaProjects-CodeAssist/3ef37a35-7870-4cde-976f-e90e5e713766/scratchpad"
    }
}
