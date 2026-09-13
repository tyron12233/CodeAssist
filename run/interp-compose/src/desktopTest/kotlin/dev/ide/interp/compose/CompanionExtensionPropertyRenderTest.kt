package dev.ide.interp.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `@Composable` EXTENSION property declared on a COMPANION object — `val WindowInsets.Companion.navigationBars`
 * and the rest of the window-inset family, which is how Compose exposes every one of them.
 *
 * `WindowInsets.navigationBars` evaluates `WindowInsets` to its companion, so the read resolves against
 * `WindowInsets.Companion`. The lowering looked it up on the CLASS instead, found nothing, and fell back to a
 * best-effort plain member binding — which the interpreter then tried to read reflectively off the companion
 * instance and failed with `no readable property navigationBars on …WindowInsets$Companion`, blanking the
 * preview. Reported against the Nimbus Weather store template, whose forecast list computes its bottom inset
 * that way.
 *
 * Rendered rather than merely lowered, because the binding is only half of it: the getter is a STATIC method
 * on a facade taking `(WindowInsets$Companion, Composer, Int)`, so the extension-property read has to thread
 * the live composer too.
 */
class CompanionExtensionPropertyRenderTest {

    private val header = """
        package demo
        import androidx.compose.foundation.Canvas
        import androidx.compose.foundation.layout.WindowInsets
        import androidx.compose.foundation.layout.asPaddingValues
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.layout.navigationBars
        import androidx.compose.foundation.layout.systemBars
        import androidx.compose.material.LocalContentColor
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.unit.dp
    """.trimIndent()

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = DocFile()
        override val version = 1L
        override fun length() = text.length
    }

    private class DocFile : VirtualFile {
        override val path = "Main.kt"
        override val name = "Main.kt"
        override val isDirectory = false
        override val exists = true
        override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }

    /** Lowering diagnostics for a preview body, so a gap is named rather than silently skipped. */
    private fun diagnosticsOf(body: String): List<String> {
        val code = "$header\n@Composable fun P() {\n$body\n}"
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(previewSymbolService()).program(parsed)
        val entry = program["P/0"] ?: error("no P/0; have ${program.keys}")
        return entry.diagnostics.map { it.reason }
    }

    /** Red pixels the body paints, or -1 when Skiko cannot rasterize here (then the check no-ops). */
    private fun redPixels(body: String): Int {
        val code = "$header\n@Composable fun P() {\n$body\n}"
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(previewSymbolService()).program(parsed)
        val entry = program["P/0"] ?: error("no P/0; have ${program.keys}")
        val renderer = ComposePreviewRenderer()
        val failures = ArrayList<String>()
        val w = 60
        val h = 60
        return try {
            @OptIn(ExperimentalComposeUiApi::class)
            val scene = ImageComposeScene(w, h, Density(1f)) {
                renderer.Render(
                    entry, program, emptyList(), emptyList(),
                    // `onPartialError` is also called with null to CLEAR a previous failure, so only a real
                    // Throwable counts.
                    onError = { failures += it.toString() },
                    onPartialError = { t -> t?.let { failures += it.toString() } },
                )
            }
            try {
                val bmp = Bitmap.makeFromImage(scene.render())
                assertTrue(failures.isEmpty(), "the preview reported errors: $failures")
                var red = 0
                for (y in 0 until h step 2) for (x in 0 until w step 2) {
                    val c = bmp.getColor(x, y)
                    if (((c shr 16) and 0xFF) > 180 && ((c shr 8) and 0xFF) < 70 && (c and 0xFF) < 70) red++
                }
                red
            } finally {
                scene.close()
            }
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError ||
                t.javaClass.simpleName.contains("LibraryLoad")
            ) -1 else throw t
        }
    }

    private fun assertPaints(name: String, body: String) {
        assertEquals(emptyList(), diagnosticsOf(body), "$name must lower with no gaps")
        val red = redPixels(body)
        if (red < 0) {
            println("[CompanionExtensionPropertyRenderTest] Skiko unavailable — skipping '$name'")
            return
        }
        assertTrue(red > 20, "$name should paint once the companion extension property reads; red=$red")
    }

    @Test fun navigationBarsInsetReads() = assertPaints(
        "WindowInsets.navigationBars",
        """
        val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Canvas(Modifier.fillMaxSize()) { drawRect(color = if (inset >= 0.dp) Color.Red else Color.Blue) }
        """.trimIndent(),
    )

    /** A second member of the same family, so the fix is the shape and not one hard-coded name. */
    @Test fun systemBarsInsetReads() = assertPaints(
        "WindowInsets.systemBars",
        """
        val inset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
        Canvas(Modifier.fillMaxSize()) { drawRect(color = if (inset >= 0.dp) Color.Red else Color.Blue) }
        """.trimIndent(),
    )

    /** The control: a plain MEMBER of the companion must still win over any same-named extension, and a
     *  companion read that always worked (`Color.Red`) must keep working. */
    @Test fun aPlainCompanionMemberStillReads() = assertPaints(
        "Color.Red",
        "Canvas(Modifier.fillMaxSize()) { drawRect(color = Color.Red) }",
    )

    /**
     * A property read on a BOXED value class. `LocalContentColor.current` hands one back (a CompositionLocal
     * is typed `Object`), and every member of a value class compiles to a STATIC `getAlpha-impl(long)` — the
     * box carries no `getAlpha()` for a reflective read to find. So `color.alpha` failed with "no readable
     * property `alpha` on androidx.compose.ui.graphics.Color". Nimbus Weather's weather glyphs dim their
     * cloud colour exactly that way.
     */
    @Test fun aPropertyOfABoxedValueClassReads() = assertPaints(
        "LocalContentColor.current.alpha",
        "val base = LocalContentColor.current\n" +
            "Canvas(Modifier.fillMaxSize()) { drawRect(color = Color.Red.copy(alpha = base.alpha)) }",
    )

    /** The same read on the UNBOXED form the interpreter normally holds, so the fix did not trade one
     *  representation for the other. */
    @Test fun aPropertyOfAnUnboxedValueClassStillReads() = assertPaints(
        "Color(…).alpha",
        "val base = Color(0xFFFF0000)\n" +
            "Canvas(Modifier.fillMaxSize()) { drawRect(color = Color.Red.copy(alpha = base.alpha)) }",
    )
}
