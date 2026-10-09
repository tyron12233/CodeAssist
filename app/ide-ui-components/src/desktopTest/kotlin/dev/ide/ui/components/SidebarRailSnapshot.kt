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
 * Off-screen renders of the sidebar: the left and right tool-window stripes with docked panes, the stripes
 * alone, a floating pane in a short window, and the mobile segmented switcher. Not an assertion; the PNGs are
 * for eyeballing the design.
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
        val left = panels.dropLast(1)
        val right = panels.takeLast(1)
        // The wide layout: left stripe + docked Search pane · editor · docked AI pane + right stripe.
        snapshot("sidebar-rail-search.png", 2000, 900) {
            Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                Row(Modifier.fillMaxSize()) {
                    ActivityRail(
                        panels = left,
                        selectedId = "search",
                        onSelect = {},
                        footer = {
                            RailActionItem(CaIcons.terminal, "Build", active = true) {}
                            RailDivider()
                            RailActionItem(CaIcons.ellipsis, "More") {}
                            RailActionItem(CaIcons.gear, "Settings") {}
                        },
                    )
                    SidebarPane(left, "search", RailSide.Left, paneWidth = 280.dp, onResize = {})
                    Box(Modifier.weight(1f).fillMaxHeight().background(Ide.colors.editorBg))
                    SidebarPane(right, "ai", RailSide.Right, paneWidth = 300.dp, onResize = {})
                    ActivityRail(panels = right, selectedId = "ai", onSelect = {}, side = RailSide.Right, menu = {})
                }
            }
        }
        // Nothing open: just the two stripes framing the editor.
        snapshot("sidebar-rail-structure.png", 2000, 900) {
            Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                Row(Modifier.fillMaxSize()) {
                    ActivityRail(
                        panels = left,
                        selectedId = null,
                        onSelect = {},
                        footer = {
                            RailActionItem(CaIcons.terminal, "Build") {}
                            RailDivider()
                            RailActionItem(CaIcons.ellipsis, "More") {}
                            RailActionItem(CaIcons.gear, "Settings") {}
                        },
                    )
                    Box(Modifier.weight(1f).fillMaxHeight().background(Ide.colors.editorBg))
                    ActivityRail(panels = right, selectedId = null, onSelect = {}, side = RailSide.Right, menu = {})
                }
            }
        }
        // A short, narrow window (phone landscape): the pane floats over a dimmed editor, the footer stays pinned.
        snapshot("sidebar-rail-floating.png", 1720, 760) {
            Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                Row(Modifier.fillMaxSize()) {
                    ActivityRail(
                        panels = left,
                        selectedId = "files",
                        onSelect = {},
                        footer = {
                            RailActionItem(CaIcons.terminal, "Build") {}
                            RailDivider()
                            RailActionItem(CaIcons.ellipsis, "More") {}
                            RailActionItem(CaIcons.gear, "Settings") {}
                        },
                    )
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
                        SidebarPane(left, "files", RailSide.Left, Modifier.align(Alignment.CenterStart), paneWidth = 280.dp, floating = true)
                    }
                    ActivityRail(panels = right, selectedId = null, onSelect = {}, side = RailSide.Right, menu = {})
                }
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
            // Pump several frames so the pane's open animation settles before capture.
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
        val OUT_DIR: String = java.io.File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }.absolutePath
    }
}
