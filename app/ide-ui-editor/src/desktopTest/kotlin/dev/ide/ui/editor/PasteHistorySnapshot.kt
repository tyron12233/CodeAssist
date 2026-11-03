package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.editor.core.ClipboardHistory
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Paste from History: the list (renders, and a tap picks an entry) and the caret toolbar's clock. PNGs too. */
@OptIn(ExperimentalComposeUiApi::class)
class PasteHistorySnapshot {
    @AfterTest fun reset() = ClipboardHistory.clear()

    @Test
    fun theListShowsNewestFirstAndATapPicksAnEntry() {
        ClipboardHistory.record("val name = \"world\"")
        ClipboardHistory.record("fun greet(name: String) {\n    println(\"Hello, \$name\")\n    log(name)\n    count++\n}")
        ClipboardHistory.record("Modifier.padding(16.dp)")
        var picked: String? = null
        val scene = ImageComposeScene(width = 760, height = 900, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Column(Modifier.fillMaxSize().background(Ide.colors.editorBg).padding(8.dp)) {
                    SelectionToolbar(hasSelection = false, onCopy = {}, onCut = {}, onPaste = {}, onSelectAll = {}, onPasteHistory = {})
                    Box(Modifier.padding(top = 12.dp)) {
                        PasteHistoryList(ClipboardHistory.entries, 340.dp, onPick = { picked = it }, onClear = {})
                    }
                }
            }
        }
        try {
            var t = 0L
            repeat(40) { t += 16_000_000L; scene.render(t) } // the toolbar's items pop in
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/paste-history.png").apply { parentFile?.mkdirs() }.writeBytes(png)
            // The second row (the multi-line function): header ~44dp, first row ~38dp, below the 8+40+12dp offset.
            val y = (8 + 40 + 12 + 44 + 38 + 30) * 2f
            scene.sendPointerEvent(PointerEventType.Press, Offset(300f, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(300f, y))
            scene.render(t + 32_000_000L)
            assertEquals(ClipboardHistory.entries[1], picked)
        } finally {
            scene.close()
        }
    }
}
