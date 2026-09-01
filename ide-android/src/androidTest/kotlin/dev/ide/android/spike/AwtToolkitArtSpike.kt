package dev.ide.android.spike

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.DexPeerFactory
import dev.ide.android.preview.AndroidRenderBackend
import dev.ide.awt.ToolkitWindows
import dev.ide.awt.interp.AwtNameRemapper
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.ReflectiveBridge
import dev.ide.jvm.Vm
import dev.ide.jvm.interpretedMethods
import dev.ide.preview.RCanvas
import dev.ide.preview.RGraphics
import dev.ide.preview.RImage
import dev.ide.preview.RPaint
import dev.ide.preview.RPath
import dev.ide.swing.JButton
import dev.ide.swing.JFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (ART) proof that a Java **Swing** program runs on CodeAssist.
 *
 * The fixture (`swingfixture.SwingFixture`) is an ordinary Swing app compiled against the REAL
 * `java.awt`/`javax.swing` on a desktop JDK. Its class bytes are bundled as an asset, never dexed and never
 * loaded by ART. Three owned pieces have to cooperate for it to draw anything here:
 *
 *  1. [AwtNameRemapper] rewrites its `java/awt` + `javax/swing` references onto `:awt-toolkit` as the VM reads
 *     each class. Android has no AWT at all, and `java.*` is not app-definable, so the toolkit is mirrored one
 *     package over and the reference is moved instead.
 *  2. The `:jvm-interp` VM interprets the fixture and BRIDGES the toolkit, which is ordinary dexed app code in
 *     the APK. So nothing about the toolkit is dynamically loaded.
 *  3. [DexPeerFactory] realizes the program's `class DrawPanel extends JPanel` as a real subclass of the
 *     toolkit's `dev.ide.swing.JPanel`, D8-compiled at runtime, whose `paintComponent` override dispatches
 *     back into the interpreter. This is the same peer path the real-view layout preview already uses, with a
 *     toolkit supertype in place of `android.view.View`.
 *
 * The assertions are real device pixels off a real `android.graphics.Canvas`, so a passing run also says the
 * toolkit's `Graphics2D` maps correctly onto Android's drawing primitives and text metrics.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.AwtToolkitArtSpike
 *     adb logcat -d -s AwtToolkitArt
 *
 * The asset is staged from `:awt-toolkit`'s test output by the `bundleAwtFixtureAsset` Gradle task, so the
 * bytes here are always the fixture the desktop suite runs against, never a stale copy.
 */
@RunWith(AndroidJUnit4::class)
class AwtToolkitArtSpike {

    private fun log(message: String) {
        Log.i("AwtToolkitArt", message)
        println(message)
    }

    @After
    fun closeWindows() {
        // The window registry is process-global, so a failed run must not leak a window into the next test.
        ToolkitWindows.disposeAll()
    }

    @Test
    fun anInterpretedSwingProgramDrawsOnArt() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val vm = Vm(
            source = ClassBytesSource { internalName ->
                if (!internalName.startsWith(FIXTURES)) return@ClassBytesSource null
                val leaf = internalName.substringAfterLast('/')
                runCatching { assets.open("awt/$leaf.class").use { it.readBytes() } }.getOrNull()
                    ?.let { AwtNameRemapper.remap(it) }
            },
            policy = InterpretPolicy { it.startsWith(FIXTURES) },
            bridge = ReflectiveBridge(),
            peerFactory = DexPeerFactory(),
        )

        val main = vm.interpretedMethods("$FIXTURE_PACKAGE.SwingFixture").single { it.name == "main" && it.isStatic }
        main.invoke(null, listOf(emptyArray<String>()))
        log("interpreted main() returned after ${vm.steps} bytecode steps")

        // 1. The program built a real toolkit window.
        val frame = ToolkitWindows.displayable().single() as JFrame
        assertEquals("the program sized its own frame", 240, frame.getWidth())
        assertEquals(160, frame.getHeight())

        // 2. Its panel subclass reached ART as a dexed peer of the toolkit's JPanel.
        val panel = frame.getContentPane().components().first { it !is JButton }
        assertEquals(
            "the interpreted subclass is a peer of the owned JPanel",
            "dev.ide.swing.JPanel",
            (panel as Any).javaClass.superclass?.name,
        )
        log("peer class on device: ${panel.javaClass.name}")

        // 3. Painting runs the interpreted paintComponent override through a real android.graphics.Canvas.
        val bitmap = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888)
        val backend = RecordingRenderBackend(Canvas(bitmap))
        frame.attachBackend(backend)
        frame.paintTo(backend)

        // The override fills a 50x20 rect in its own colour at (10,10) of the centre panel, which BorderLayout
        // puts at the top of the frame, so that rect is at (10,10) in window space too.
        val painted = bitmap.getPixel(30, 20)
        assertEquals(
            "the interpreted paintComponent drew its rect (got ${Integer.toHexString(painted)})",
            FIXTURE_BLUE, painted,
        )
        assertTrue("drew the count: ${backend.texts}", backend.texts.any { it == "clicks: 0" })
        log("first frame drew ${backend.texts}")

        // 4. A click reaches the interpreted lambda, mutates the interpreted field, and shows up next frame.
        val button = frame.getContentPane().components().filterIsInstance<JButton>().single()
        val bounds = button.getBounds()
        frame.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)

        backend.texts.clear()
        frame.paintTo(backend)
        assertTrue(
            "the interpreted listener ran on device: ${backend.texts}",
            backend.texts.any { it == "clicks: 1" },
        )
        log("after the click: ${backend.texts}")

        // 5. EXIT_ON_CLOSE closes the window instead of the process, which is what ends a GUI run.
        assertEquals(JFrame.EXIT_ON_CLOSE, frame.getDefaultCloseOperation())
        frame.close()
        assertTrue("closing disposed the window", ToolkitWindows.displayable().isEmpty())
    }

    /**
     * The production [AndroidRenderBackend], plus a record of the text it drew. Asserting on the strings is
     * how the click test checks the interpreted listener ran, without reading glyph pixels; the SHAPES are
     * still asserted against the real bitmap.
     */
    private class RecordingRenderBackend(canvas: Canvas) : RCanvas, RGraphics {
        private val delegate = AndroidRenderBackend(canvas)
        val texts = ArrayList<String>()

        override fun save(): Int = delegate.save()
        override fun restore() = delegate.restore()
        override fun translate(dx: Float, dy: Float) = delegate.translate(dx, dy)
        override fun clipRect(l: Float, t: Float, r: Float, b: Float) = delegate.clipRect(l, t, r, b)
        override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: RPaint) =
            delegate.drawRect(l, t, r, b, paint)
        override fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: RPaint) =
            delegate.drawRoundRect(l, t, r, b, rx, ry, paint)
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: RPaint) =
            delegate.drawCircle(cx, cy, radius, paint)
        override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: RPaint) =
            delegate.drawLine(x0, y0, x1, y1, paint)
        override fun drawPath(path: RPath, paint: RPaint) = delegate.drawPath(path, paint)
        override fun drawImage(img: RImage, l: Float, t: Float, r: Float, b: Float, tintArgb: Int?) =
            delegate.drawImage(img, l, t, r, b, tintArgb)

        override fun drawText(text: CharSequence, x: Float, y: Float, paint: RPaint) {
            texts.add(text.toString())
            delegate.drawText(text, x, y, paint)
        }

        override fun newPaint(): RPaint = delegate.newPaint()
        override fun parsePath(pathData: String): RPath? = delegate.parsePath(pathData)
        override fun measureText(text: CharSequence, paint: RPaint): Float = delegate.measureText(text, paint)
        override fun fontMetrics(paint: RPaint) = delegate.fontMetrics(paint)
    }

    private companion object {
        const val FIXTURE_PACKAGE = "swingfixture"
        const val FIXTURES = "swingfixture/"

        /** `new Color(0x2D, 0x6C, 0xDF)` in the fixture, opaque. */
        const val FIXTURE_BLUE = 0xFF2D6CDF.toInt()
    }
}
