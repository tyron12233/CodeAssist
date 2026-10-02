package dev.ide.interp.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.ide.lang.kotlin.interp.PreviewWire
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * A lowered `@Preview` rendered to pixels with nothing but this module, its interpreter and the project's
 * own Compose: decode, interpret into an offscreen `ImageComposeScene`, return the frame.
 *
 * This is how a host with no JVM previews. It runs the whole desktop pipeline (the tree interpreter, the
 * Compose bridge, the renderer) inside `:jvm-vm` next to the project's libraries, so the interpreter's
 * reflection sees the project's Compose exactly as it sees the bundled one on the desktop, and the only
 * thing that crosses from the host is [encode]'s blob.
 */
object VmPreviewEntry {

    /**
     * The wire form of a lowered preview: its entry point, every lowered function, and the source classes.
     * [PreviewWire] is common code, so a host encodes this itself; this copy is for JVM callers.
     */
    @JvmStatic
    fun encode(entry: ResolvedFunction, program: Map<String, ResolvedFunction>, classes: List<ResolvedClass>): ByteArray =
        PreviewWire.encode(entry, program, classes)

    /**
     * The preview in [blob] rendered at [width]x[height] pixels under the `@Preview` options a host passes,
     * as `[png, problems]`: the PNG bytes, and what went wrong as text (null when nothing did). A preview
     * that fails part way still renders what it could, so a frame comes back with its problems.
     *
     * [fontScale] scales text as the system setting would; [dark] is the night theme
     * (`isSystemInDarkTheme()`); [background] is an ARGB colour painted behind the preview, or 0 for none.
     */
    @JvmStatic
    fun renderFrame(blob: ByteArray, width: Int, height: Int, density: Float, fontScale: Float, dark: Boolean, background: Long): Array<Any?> {
        val frame = render(blob, width, height, density, fontScale, dark, background)
        val png = frame.image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        return arrayOf(png, frame.problems.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    /** The preview in [blob] rendered at [width]x[height] pixels, as a PNG. */
    @JvmStatic
    fun renderPng(blob: ByteArray, width: Int, height: Int, density: Float): ByteArray =
        render(blob, width, height, density).image.encodeToData(EncodedImageFormat.PNG)!!.bytes

    /** A hash of every pixel of the rendered frame, to compare two renders by. */
    @JvmStatic
    fun renderSignature(blob: ByteArray, width: Int, height: Int, density: Float): Long =
        signature(render(blob, width, height, density).image)

    /** `WxH inked=N`, then any problems: the frame's size and how many pixels differ from its top-left one. */
    @JvmStatic
    fun renderSummary(blob: ByteArray, width: Int, height: Int, density: Float): String {
        val frame = render(blob, width, height, density)
        val image = frame.image
        val pixels = Bitmap.makeFromImage(image).readPixels() ?: return "${image.width}x${image.height} no pixels"
        var inked = 0
        for (k in pixels.indices step 4) {
            if (pixels[k] != pixels[0] || pixels[k + 1] != pixels[1] || pixels[k + 2] != pixels[2]) inked++
        }
        val problems = if (frame.problems.isEmpty()) "" else " problems=" + frame.problems.joinToString(" | ")
        return "${image.width}x${image.height} inked=$inked$problems"
    }

    /** [renderSignature] with the problems the render reported, for a check that wants both. */
    @JvmStatic
    fun renderChecked(blob: ByteArray, width: Int, height: Int, density: Float): String {
        val frame = render(blob, width, height, density)
        return signature(frame.image).toString() + frame.problems.joinToString("") { "\n$it" }
    }

    @JvmStatic
    fun signature(image: Image): Long {
        val pixels = Bitmap.makeFromImage(image).readPixels() ?: return 0L
        var h = 1125899906842597L
        for (b in pixels) h = 31 * h + b
        return h
    }

    // ---- live sessions: a preview that stays composed, takes input and animates ----------------------------

    private class Session(val scene: ImageComposeScene, val problems: MutableList<String>) {
        val opened = System.nanoTime()
        var reported = 0
    }

    private val sessions = HashMap<Int, Session>()
    private var nextSession = 1

    /**
     * Composes the preview in [blob] into a scene that stays open, for a host that shows a live preview: it
     * can send input ([pointer]) and render again ([frame]) as state and animations change. Returns the
     * session's id; [close] releases it.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @JvmStatic
    fun open(blob: ByteArray, width: Int, height: Int, density: Float, fontScale: Float, dark: Boolean, background: Long): Int {
        val (entry, program, classes) = PreviewWire.decode(blob)
        val renderer = ComposePreviewRenderer()
        val problems = ArrayList<String>()
        val scene = ImageComposeScene(width, height, Density(density, fontScale)) {
            Themed(dark) {
                val content = @Composable {
                    renderer.Render(
                        entry, program, classes,
                        onError = { problems.add(describe(it)) },
                        onPartialError = { if (it != null) problems.add(describe(it)) },
                    )
                }
                if (background != 0L) Box(Modifier.background(Color(background.toInt()))) { content() } else content()
            }
        }
        val id = nextSession++
        sessions[id] = Session(scene, problems)
        return id
    }

    /**
     * The session's current frame, as `[png, problems, animating]`: the PNG, the problems reported since the
     * last frame (null when none), and whether the scene still has work pending (an animation, a state change
     * an effect made), in which case the host should ask for another frame soon.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @JvmStatic
    fun frame(id: Int): Array<Any?> {
        val session = sessions[id] ?: return arrayOf(null, "no preview session $id", false)
        val image = session.scene.render(System.nanoTime() - session.opened)
        val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        val fresh = session.problems.drop(session.reported).distinct()
        session.reported = session.problems.size
        return arrayOf(png, fresh.takeIf { it.isNotEmpty() }?.joinToString("\n"), session.scene.hasInvalidations())
    }

    /**
     * Input to the session's scene: [kind] is 0 for a press, 1 for a move, 2 for a release, at ([x], [y]) in
     * the scene's pixels.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @JvmStatic
    fun pointer(id: Int, kind: Int, x: Float, y: Float) {
        val session = sessions[id] ?: return
        val type = when (kind) {
            0 -> PointerEventType.Press
            2 -> PointerEventType.Release
            else -> PointerEventType.Move
        }
        session.scene.sendPointerEvent(type, Offset(x, y), timeMillis = (System.nanoTime() - session.opened) / 1_000_000)
    }

    @JvmStatic
    fun close(id: Int) {
        sessions.remove(id)?.scene?.close()
    }

    private class Frame(val image: Image, val problems: List<String>)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        blob: ByteArray,
        width: Int,
        height: Int,
        density: Float,
        fontScale: Float = 1f,
        dark: Boolean = false,
        background: Long = 0L,
    ): Frame {
        val (entry, program, classes) = PreviewWire.decode(blob)
        val renderer = ComposePreviewRenderer()
        val problems = ArrayList<String>()
        val scene = ImageComposeScene(width, height, Density(density, fontScale)) {
            Themed(dark) {
                val content = @Composable {
                    renderer.Render(
                        entry, program, classes,
                        onError = { problems.add(describe(it)) },
                        onPartialError = { if (it != null) problems.add(describe(it)) },
                    )
                }
                if (background != 0L) Box(Modifier.background(Color(background.toInt()))) { content() } else content()
            }
        }
        try {
            return Frame(scene.render(), problems.distinct())
        } finally {
            scene.close()
        }
    }

    /** [content] under the requested system theme, which is what `isSystemInDarkTheme()` reads. */
    @OptIn(InternalComposeUiApi::class)
    @Composable
    private fun Themed(dark: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) { content() }
    }

    private fun describe(t: Throwable): String = buildString {
        append(t::class.simpleName ?: "Error")
        t.message?.let { append(": ").append(it) }
        var cause = t.cause
        while (cause != null && cause !== t) {
            append("\ncaused by ").append(cause::class.simpleName)
            cause.message?.let { append(": ").append(it) }
            cause = cause.cause
        }
    }
}
