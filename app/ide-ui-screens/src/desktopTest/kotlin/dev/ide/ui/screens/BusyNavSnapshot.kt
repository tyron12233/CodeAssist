package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** The editor's bottom bar with the AI agent mid-reply, at three points of the busy animation. Not an assertion. */
class BusyNavSnapshot {
    @Test
    fun renderBusyAiItem() {
        val scene = ImageComposeScene(width = 822, height = 140, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    BottomNav(
                        selected = null,
                        onSelect = {},
                        rightTool = RightToolNavItem(CaIcons.sparkle, "AI", open = false, onClick = {}, busy = true),
                    )
                }
            }
        }
        try {
            val out = System.getenv("SNAPSHOT_DIR") ?: "build/snapshots"
            File(out).mkdirs()
            for ((i, t) in listOf(0L, 300_000_000L, 700_000_000L).withIndex()) {
                File("$out/busy-nav-$i.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        } finally {
            scene.close()
        }
    }
}
