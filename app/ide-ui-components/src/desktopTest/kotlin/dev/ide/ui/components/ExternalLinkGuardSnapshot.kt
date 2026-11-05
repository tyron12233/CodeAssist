package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Density
import dev.ide.ui.markdown.linkify
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tap on a link in selectable store text reaches the guard (not the browser) and draws its warning dialog;
 * Open then hands the link to the platform. Writes the dialog to `codeassist-snapshots/link-guard.png`.
 */
class ExternalLinkGuardSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun tapAsksFirstThenOpens() {
        val opened = mutableListOf<String>()
        val platform = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        val url = "http://github.com@192.168.4.20/CodeAssist/releases/latest"
        val scene = ImageComposeScene(width = 822, height = 1400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                CompositionLocalProvider(LocalUriHandler provides platform) {
                    ExternalLinkGuard {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            SelectionContainer {
                                Text(linkify(url, SpanStyle(), SpanStyle(color = MaterialTheme.colorScheme.primary)))
                            }
                        }
                    }
                }
            }
        }
        try {
            scene.render()
            scene.sendPointerEvent(PointerEventType.Press, Offset(30f, 15f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(30f, 15f))
            for (ms in 50L..1500L step 50L) scene.render(ms * 1_000_000L)
            val png = scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots"), "link-guard.png")
            out.parentFile.mkdirs()
            out.writeBytes(png)
            println("wrote snapshot: $out")
            assertTrue(opened.isEmpty(), "the tap went straight to the browser: $opened")
            // Open: the stacked buttons' first row, found by the dialog's layout at this size.
            scene.sendPointerEvent(PointerEventType.Press, Offset(588f, 992f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(588f, 992f))
            scene.render(2_100_000_000L)
            assertEquals(listOf(url), opened)
        } finally {
            scene.close()
        }
    }
}
