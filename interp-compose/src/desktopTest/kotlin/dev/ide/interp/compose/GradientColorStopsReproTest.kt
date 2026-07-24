package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `Brush.linearGradient(colorStops = arrayOf(0f to Color(0xFF000000), …))` must build a real `LinearGradient`,
 * not crash. The interpreter keeps the `Color` value class UNBOXED (a `Long`), so `stop to color` builds
 * `Pair(Float, Long)`; passed into the `vararg colorStops: Pair<Float, Color>` param, the callee reads back
 * `pair.second as Color` and — with a raw `Long` there — the previewed app hard-crashed at draw time
 * (`FATAL: kotlin.Pair/Long cannot be cast to androidx.compose.ui.graphics.Color`, in `makeTransparentColors`).
 * The dispatcher now boxes value-class components of a Pair (incl. inside a Collection/Array of Pairs).
 */
class GradientColorStopsReproTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    @Test
    fun linearGradientColorStopsBoxesTheColorComponent() {
        val code = """
            package demo
            import androidx.compose.ui.graphics.Brush
            import androidx.compose.ui.graphics.Color
            fun box(): Any = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF000000),
                    0.6f to Color(0xFF000000),
                    1f to Color(0xFF1A0533)
                )
            )
        """.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val result = Interpreter(program, ComposeDispatcher()).call(program["box/0"]!!, emptyList())
        // The crash was a deferred cast at DRAW time (a raw Long/Pair sitting where Compose reads a Color). The
        // built Brush's own rendering proves the fix: `colors=[Color(…)]` (boxed) + `stops=[…]` (the vararg
        // colorStops overload was chosen), never `colors=[(0.0, -72…)]` (raw Pairs) / `stops=null`.
        val shown = result?.toString().orEmpty()
        assertTrue(
            result != null && result.javaClass.simpleName == "LinearGradient" &&
                shown.contains("colors=[Color(") && shown.contains("stops=[0.0, 0.6, 1.0]"),
            "colorStops Pairs must box their Color component + pick the vararg overload; was $result",
        )
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F(); override val version = 1L
        override fun length() = text.length
    }
    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
