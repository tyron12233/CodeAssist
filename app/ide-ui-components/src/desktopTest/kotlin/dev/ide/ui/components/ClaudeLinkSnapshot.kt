package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The editor's top bar with the Claude chip (connected, working) on a phone and a wide window, and Home's
 * one-time "Use with Claude" card. Judged by eye; the assertion only catches a blank frame.
 */
class ClaudeLinkSnapshot {

    @Composable
    private fun bars(compact: Boolean) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for (status in listOf(ClaudeLinkStatus.Connected, ClaudeLinkStatus.Working, ClaudeLinkStatus.Off)) {
                EditorTopBar(
                    projectName = "snow-globe",
                    indexStatus = IndexUiStatus(),
                    onToggleNav = {},
                    onBack = {},
                    onOpenPalette = {},
                    hasActiveFile = true,
                    compact = compact,
                    claudeLink = status,
                    onUseWithClaude = {},
                )
            }
        }
    }

    @Test
    fun phoneDark() = render("claude-chip-phone-dark.png", 411, 260, dark = true) { bars(compact = true) }

    @Test
    fun phoneLight() = render("claude-chip-phone-light.png", 411, 260, dark = false) { bars(compact = true) }

    @Test
    fun wideDark() = render("claude-chip-wide-dark.png", 1100, 260, dark = true) { bars(compact = false) }

    @Test
    fun announcementCard() = render("claude-home-card-light.png", 411, 220, dark = false) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            ClaudeAnnouncementCard(onSetUp = {}, onDismiss = {})
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, wDp: Int, hDp: Int, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = dark) { content() }
        }
        try {
            scene.render()
            // Partway into the pulse, so the working chip shows its ring.
            for (frame in 1..30) scene.render(frame * 16_666_667L)
            val png = scene.render(550_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(System.getenv("SNAPSHOT_DIR") ?: "build/snapshots").apply { mkdirs() }
            File(out, name).writeBytes(png)
            assertTrue(png.size > 5_000, "$name should render more than a blank frame")
        } finally {
            scene.close()
        }
    }
}
