package dev.ide.interp.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import org.jetbrains.skia.Bitmap
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The reported bug end-to-end: a **gradient-based shadow animation** — an animated value (rememberInfinite
 * transition) driving a `Brush.linearGradient` painted in `Modifier.drawBehind` — rendered through the full
 * interpreter → compose → layout → DRAW pipeline (`ImageComposeScene`, real Skiko pixels), sampled at several
 * advancing frame times. If the animation runs, the sampled pixels change frame-to-frame; if it's frozen, they
 * don't.
 *
 * A real, normally-COMPILED copy of the exact same composable ([RealGradientShadow]) is the control: it proves
 * the headless scene actually ticks the animation (so an "identical every frame" result on the interpreted
 * side is a genuine interpreter defect, not a dead frame clock). Skiko-gated like [CanvasRenderTest].
 */
class GradientShadowPixelReproTest {

    private val times = longArrayOf(0L, 120_000_000L, 260_000_000L, 400_000_000L, 540_000_000L)

    private val SOURCE = """
        package demo
        import androidx.compose.animation.core.RepeatMode
        import androidx.compose.animation.core.animateFloat
        import androidx.compose.animation.core.infiniteRepeatable
        import androidx.compose.animation.core.rememberInfiniteTransition
        import androidx.compose.animation.core.tween
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.getValue
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.draw.drawBehind
        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Brush
        import androidx.compose.ui.graphics.Color
        @Composable fun P() {
            val transition = rememberInfiniteTransition(label = "t")
            val shift by transition.animateFloat(
                initialValue = 0.05f,
                targetValue = 0.95f,
                animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                label = "s",
            )
            Box(Modifier.fillMaxSize().drawBehind {
                drawRect(brush = Brush.linearGradient(
                    colors = listOf(Color.Red, Color.Blue),
                    start = Offset(0f, 0f),
                    end = Offset(size.width * shift, size.height * shift),
                ))
            })
        }
    """.trimIndent()

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    /** Per-frame pixel signatures for the INTERPRETED preview of [SOURCE], or null if Skiko can't rasterize. */
    private fun interpretedSignatures(): List<Long>? {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(GsDoc(SOURCE)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val entry = program["P/0"] ?: error("no P/0; have ${program.keys}")
        val renderer = ComposePreviewRenderer()
        return signaturesOf { renderer.Render(entry, program, emptyList(), emptyList(), onError = {}, onPartialError = {}) }
    }

    /** Per-frame pixel signatures for the real, compiled control composable. */
    private fun realSignatures(): List<Long>? = signaturesOf { RealGradientShadow() }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun signaturesOf(content: @Composable () -> Unit): List<Long>? {
        val w = 80; val h = 80
        return try {
            val scene = ImageComposeScene(w, h, Density(1f), content = content)
            try {
                times.map { t ->
                    val img = scene.render(t)
                    val bmp = Bitmap.makeFromImage(img)
                    var sig = 1125899906842597L
                    var i = 0
                    for (y in 0 until h step 4) for (x in 0 until w step 4) {
                        val c = bmp.getColor(x, y)
                        sig = 31 * sig + (c.toLong() xor (i.toLong() * 0x9E3779B1L)); i++
                    }
                    sig
                }
            } finally { scene.close() }
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) null else throw t
        }
    }

    @Test
    fun gradientShadowAnimationAnimatesAcrossFrames() {
        val real = realSignatures()
        if (real == null) { println("[GradientShadowPixelReproTest] Skiko unavailable — skipping"); return }
        // Control: the headless scene MUST tick the animation, else the interpreted result is meaningless.
        assertTrue(real.toSet().size >= 2, "control (compiled) gradient-shadow animation must change across frames — else the scene's frame clock isn't ticking; sigs=$real")

        val interp = interpretedSignatures()
            ?: run { println("[GradientShadowPixelReproTest] Skiko unavailable — skipping"); return }
        assertTrue(
            interp.toSet().size >= 2,
            "INTERPRETED gradient-shadow animation is FROZEN — the same pixels render every frame while the compiled control animates. " +
                "interpreted sigs=$interp ; control sigs=$real",
        )
    }

    private class GsDoc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = GsF(); override val version = 1L
        override fun length() = text.length
    }
    private class GsF : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}

/** The compiled control — byte-for-byte the same composable as the interpreted [GradientShadowPixelReproTest.SOURCE]. */
@Composable
private fun RealGradientShadow() {
    val transition = rememberInfiniteTransition(label = "t")
    val shift by transition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "s",
    )
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Red, Color.Blue),
                start = Offset(0f, 0f),
                end = Offset(size.width * shift, size.height * shift),
            ),
        )
    })
}
