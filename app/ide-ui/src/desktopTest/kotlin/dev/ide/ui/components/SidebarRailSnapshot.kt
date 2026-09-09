package dev.ide.ui.components

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Off-screen renders of the new sidebar: the left activity rail (built-in panels + a plugin), with its sliding
 * selection indicator on different panels, and the mobile segmented switcher. Not an assertion — the PNGs are
 * for eyeballing the design (the sliding indicator, labels, and pane).
 */
class SidebarRailSnapshot {

    private fun panels(): List<SidebarPanel> = listOf(
        SidebarPanel("files", "Files", CaIcons.docText, 10) { PaneStub("Files", MaterialTheme.colorScheme.primary) },
        SidebarPanel("search", "Search", CaIcons.search, 20) { PaneStub("Search", Ide.colors.info) },
        SidebarPanel("structure", "Structure", CaIcons.code, 30) { PaneStub("Structure", Ide.colors.warning) },
        SidebarPanel("source", "Source", CaIcons.gitBranch, 40) { PaneStub("Source", Ide.colors.success) },
        SidebarPanel("ai", "AI", CaIcons.sparkle, 1000) { PaneStub("AI", MaterialTheme.colorScheme.primary) },
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderRailAndPane() {
        val panels = panels()
        // Left rail (Search selected) + its docked pane, as the ExpandedLayout composes them.
        snapshot("sidebar-rail-search.png", 460, 520) {
            Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                Row(Modifier.fillMaxSize()) {
                    ActivityRail(
                        panels = panels,
                        selectedId = "search",
                        onSelect = {},
                        header = { ProjectTile("Demo", size = 42.dp) },
                        footer = {
                            RailActionItem(CaIcons.ellipsis, "More") {}
                            RailActionItem(CaIcons.gear, "Settings") {}
                        },
                    )
                    SidebarPane(panels, "search", RailSide.Left, paneWidth = 260.dp)
                    Box(Modifier.fillMaxHeight().width(120.dp).background(Ide.colors.editorBg))
                }
            }
        }
        // Same rail with Structure selected — the indicator sits on a different icon.
        snapshot("sidebar-rail-structure.png", 200, 520) {
            Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                ActivityRail(
                    panels = panels,
                    selectedId = "structure",
                    onSelect = {},
                    header = { ProjectTile("Demo", size = 42.dp) },
                    footer = {
                        RailActionItem(CaIcons.ellipsis, "More") {}
                        RailActionItem(CaIcons.gear, "Settings") {}
                    },
                )
            }
        }
        // The mobile in-drawer segmented switcher.
        snapshot("sidebar-segmented.png", 380, 80) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                SegmentedPanelSwitcher(panels = panels.take(3), selectedId = "search", onSelect = {})
            }
        }
    }

    @Composable
    private fun PaneStub(label: String, color: androidx.compose.ui.graphics.Color) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Box(Modifier.fillMaxWidth().background(color.copy(alpha = 0.14f)).padding(12.dp)) {
                Text(label, color = color)
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content() }
        }
        try {
            // Pump several frames so the measured sliding indicator (onGloballyPositioned → state → relayout)
            // and its spring settle before capture — a single frame renders before the measurement lands.
            scene.render()
            var t = 0L
            repeat(12) { t += 32_000_000L; scene.render(t) }
            val img = scene.render(t + 200_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = "$OUT_DIR/$name"
            File(out).apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $out (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "/private/tmp/claude-501/-Users-tyronscott-JavaProjects-CodeAssist/5b990da4-7091-443b-954a-44e3c60dc588/scratchpad"
    }
}
