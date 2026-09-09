package dev.ide.interp.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The implicit `it` of a RECEIVER lambda with one value parameter must resolve — `LazyColumn { items(n) {
 * Text("Item: $it") } }`, whose `itemContent` is `LazyItemScope.(index: Int) -> Unit`. `it` is the index,
 * bound AFTER the `<this>` receiver. It was "unresolved name `it`" ("Preview not interpretable") because the
 * lowering synthesized the implicit `it` only for a receiver-LESS lambda. Also covers the named-key lambda
 * (`key = { it }`, `((Int) -> Any)?`) alongside a trailing itemContent using `it`.
 */
class LazyItemsImplicitItTest {


    private fun program(code: String): Map<String, dev.ide.lang.kotlin.interp.ResolvedFunction> {
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(LDoc(code)) as KotlinParsedFile
        return KotlinPreviewLowering(service).program(parsed)
    }

    private fun gaps(code: String): List<String> {
        val fn = program(code)["P/0"] ?: error("no P/0")
        val g = ArrayList<String>()
        fn.body.walk { if (it is RNode.Unsupported) g += "${it.reason}: ${it.text}" }
        return g
    }

    private val HEADER = """
        package demo
        import androidx.compose.foundation.layout.fillMaxWidth
        import androidx.compose.foundation.lazy.LazyColumn
        import androidx.compose.foundation.lazy.items
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
    """.trimIndent()

    @Test fun itemsWithImplicitItResolves() {
        val code = "$HEADER\n@Composable fun P() { LazyColumn(Modifier.fillMaxWidth()) { items(100) { Text(\"Item: \$it\") } } }"
        assertTrue(gaps(code).isEmpty(), "items(100) { Text(\"\$it\") } must resolve its implicit index `it`; gaps=${gaps(code)}")
    }

    @Test fun itemsWithKeyItAndContentItResolves() {
        val code = "$HEADER\n@Composable fun P() { LazyColumn(Modifier.fillMaxWidth()) { items(100, key = { it }) { Text(\"Item: \$it\") } } }"
        assertTrue(gaps(code).isEmpty(), "items(100, key = { it }) { Text(\"\$it\") } must resolve both implicit `it`s; gaps=${gaps(code)}")
    }

    @Test fun lazyColumnItemsRenders() {
        val code = "$HEADER\n@Composable fun P() { LazyColumn(Modifier.fillMaxWidth()) { items(100, key = { it }) { Text(\"Item: \$it\") } } }"
        val entry = program(code)["P/0"] ?: error("no P/0")
        val prog = program(code)
        val painted = try {
            @OptIn(ExperimentalComposeUiApi::class)
            val scene = ImageComposeScene(200, 200, Density(1f)) {
                ComposePreviewRenderer().Render(entry, prog, emptyList(), emptyList(), onError = {}, onPartialError = {})
            }
            try {
                val bmp = Bitmap.makeFromImage(scene.render())
                var n = 0
                for (y in 0 until 200 step 3) for (x in 0 until 200 step 3) {
                    val c = bmp.getColor(x, y)
                    val a = (c ushr 24) and 0xFF
                    val lum = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                    if (a > 40 && lum < 235) n++
                }
                n
            } finally { scene.close() }
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) -1 else throw t
        }
        if (painted < 0) return
        assertTrue(painted > 20, "the LazyColumn items should paint their text; painted=$painted")
    }

    private class LDoc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = LF(); override val version = 1L
        override fun length() = text.length
    }
    private class LF : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
