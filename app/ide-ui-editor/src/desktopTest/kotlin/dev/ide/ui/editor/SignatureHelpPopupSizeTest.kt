package dev.ide.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.ide.ui.backend.UiSignature
import dev.ide.ui.backend.UiSignatureHelp
import dev.ide.ui.backend.UiSignatureParam
import dev.ide.ui.theme.CodeAssistTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Parameter info for a many-parameter, overloaded call (a Compose `Text`) must stay a small panel in a large
 * desktop / tablet window, not a wall of every overload in full: one compact line per the phone layout.
 */
class SignatureHelpPopupSizeTest {

    /** A `Text(param0: Type0 = …, …)`-shaped overload with [n] long parameters and correct offsets. */
    private fun overload(n: Int, tag: String): UiSignature {
        val sb = StringBuilder("Text(")
        val params = ArrayList<UiSignatureParam>()
        for (i in 0 until n) {
            if (i > 0) sb.append(", ")
            val start = sb.length
            sb.append("parameter${i}_$tag: androidx.compose.ui.text.SomeLongType$i = SomeDefault$i")
            params.add(UiSignatureParam("p$i", start, sb.length))
        }
        sb.append("): Unit")
        return UiSignature(sb.toString(), params)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun aManyParameterCallStaysCompactInALargeWindow() {
        val help = UiSignatureHelp(List(4) { overload(20, "o$it") }, activeSignature = 1, activeParameter = 10)
        var size = IntSize.Zero
        // A 1600x1000dp desktop window at density 1.
        val scene = ImageComposeScene(width = 1600, height = 1000, density = Density(1f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    SignatureHelpPopup(help, modifier = Modifier.onSizeChanged { size = it })
                }
            }
        }
        try {
            scene.render()
            // One overload line (plus the stepper and the peek hint) — the old stacked view was the full screen.
            assertTrue(size.height in 1..160, "popup height ${size.height}dp should be a few compact lines")
            assertTrue(size.width <= 560, "popup width ${size.width}dp should stay capped")
        } finally {
            scene.close()
        }
    }
}
