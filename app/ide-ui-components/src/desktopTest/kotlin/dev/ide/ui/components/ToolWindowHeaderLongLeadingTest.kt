package dev.ide.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.CodeAssistTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #1760: in the phone drawer's switcher header, a long AI model id in the panel's leading slot took the
 * whole row; the actions after it (the auto-accept chip, the menu) were squeezed until the chip's "自动接受" stood
 * one character per line, or pushed out. The leading control must give way and the actions keep their size.
 */
class ToolWindowHeaderLongLeadingTest {

    @Test
    fun aLongLeadingControlLeavesTheActionsTheirSize() {
        var chipHeight = 0f
        var chipRight = 0f
        var menuRight = 0f
        val widthPx = 624 // 312dp at 2x: the phone drawer on a 360dp screen
        val panels = listOf(
            SidebarPanel("ai", "AI", CaIcons.docText, 10) {
                ToolWindowHeaderActions(
                    leading = {
                        Text("ms/deepseek-ai/DeepSeek-V4.1-Flash-1017-with-a-very-long-suffix", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    actions = {
                        Box(Modifier.onGloballyPositioned { val b = it.boundsInRoot(); chipHeight = b.height; chipRight = b.right }) {
                            Chip("自动接受")
                        }
                        Box(Modifier.onGloballyPositioned { menuRight = it.boundsInRoot().right }) {
                            IconButtonCa(CaIcons.ellipsis, "More", {}, boxSize = 28, iconSize = 16)
                        }
                    },
                )
            },
            SidebarPanel("files", "Files", CaIcons.docText, 20) { Text("files") },
        )
        val scene = ImageComposeScene(width = widthPx, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                val slots = rememberToolWindowHeaderSlots()
                Box(Modifier.fillMaxSize()) {
                    ToolWindowSwitcherHeader(panels, "ai", {}, slots, onHide = {})
                    // The panel's body registers its header actions into the slots.
                    androidx.compose.runtime.CompositionLocalProvider(LocalToolWindowHeader provides slots.of("ai")) {
                        panels[0].content()
                    }
                }
            }
        }
        try {
            repeat(10) { scene.render(it * 16_666_667L) }
            assertTrue(chipHeight in 1f..72f, "the chip was squeezed into a column: ${chipHeight}px tall")
            assertTrue(chipRight <= widthPx && menuRight <= widthPx, "an action was pushed out: chip $chipRight, menu $menuRight of $widthPx")
            assertTrue(menuRight > 0f, "the menu button was not laid out")
        } finally {
            scene.close()
        }
    }
}
