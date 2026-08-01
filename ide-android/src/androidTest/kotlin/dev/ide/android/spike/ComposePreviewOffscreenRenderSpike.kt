package dev.ide.android.spike

import android.app.Presentation
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.core.LoweredComposePreview
import dev.ide.core.preview.ComposePreviewWireCodec
import dev.ide.interp.compose.ComposePreviewRenderer
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose-preview isolation **Phase 1a**: render a REAL `@Preview` OFF-SCREEN to a bitmap on device — the render
 * pipeline the `:preview` process will host, minus the AIDL plumbing (Phase 1b). It (1) round-trips a lowered
 * preview through [ComposePreviewWireCodec] on ART (the wire codec works on-device too), then (2) composes the
 * decoded entry via the real [ComposePreviewRenderer] inside the Phase-0 off-screen surface (`Presentation` on a
 * `VirtualDisplay` + `ImageReader`), and (3) captures a frame and confirms it drew (non-uniform pixels) with no
 * render error. This is the material3-flip render (bridged composer against the IDE's bundled Compose) running on
 * an app-owned virtual display — exactly what `:preview` will do, so a runaway there pegs `:preview`, not the IDE.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ComposePreviewOffscreenRenderSpike
 *     adb logcat -d -s ComposeOffscreenSpike
 */
@RunWith(AndroidJUnit4::class)
class ComposePreviewOffscreenRenderSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("ComposeOffscreenSpike", m); println(m) }

    private class SpikeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        fun resume() { savedState.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    }

    /** `fun Preview() { Text("Hello") }` lowered — a material3 Text against the bundled runtime (the flip). */
    private fun textPreview(): LoweredComposePreview {
        val span = SourceSpan(0, 0)
        val call = RNode.Call(
            ResolvedCallable.Library(
                "Text", "androidx.compose.material3.TextKt", "Text", listOf(null),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const("Hello", null, span))),
            callSiteKey = CallSiteKey(1), source = span,
        )
        return LoweredComposePreview(ResolvedFunction("Preview", emptyList(), RNode.Block(listOf(call), false, span), emptyList()), emptyMap())
    }

    @Test
    fun rendersAStaticPreviewOffscreenToABitmap() {
        // (1) Round-trip the lowered preview through the wire codec on ART (the same blob :preview will decode).
        val original = textPreview()
        val decoded = ComposePreviewWireCodec.decode(ComposePreviewWireCodec.encode(original))
        log("codec round-trip on ART: entry='${decoded.entry.name}' program=${decoded.program.size}")

        val ctx = instrumentation.targetContext
        val w = 320
        val h = 160
        val frameThread = HandlerThread("offscreen-frames").apply { start() }
        val frames = AtomicInteger(0)
        val lastImage = AtomicReference<IntArray?>(null)
        val renderError = AtomicReference<Throwable?>(null)

        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.use { img -> lastImage.set(readPixels(img)); frames.incrementAndGet() }
        }, Handler(frameThread.looper))

        val dm = ctx.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "ca-preview-render", w, h, 320, imageReader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val owner = SpikeOwner()
        var presentation: Presentation? = null
        try {
            instrumentation.runOnMainSync {
                owner.resume()
                val p = Presentation(ctx, vd.display)
                val renderer = ComposePreviewRenderer() // bundled Compose, no VM executor (Text is bundled)
                val composeView = ComposeView(p.context).apply {
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeViewModelStoreOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                    setContent {
                        val onErr: @Composable (Throwable) -> Unit = { t -> renderError.set(t) }
                        renderer.Render(decoded.entry, decoded.program, decoded.classes, emptyList(), onErr) {}
                    }
                }
                p.setContentView(composeView)
                p.show()
                presentation = p
            }

            waitUntil("first frame") { frames.get() > 0 }
            val px = lastImage.get()
            val nonUniform = px != null && px.any { it != px[0] }
            log("VM-OFFSCREEN: frames=${frames.get()}, non-uniform=$nonUniform, renderError=${renderError.get()?.message}")

            assertNull("the real @Preview render threw off-screen: ${renderError.get()?.message}", renderError.get())
            assertTrue("no off-screen frame was produced", frames.get() > 0)
            assertFalse("the captured frame is blank (uniform) — the preview didn't draw", px != null && px.all { it == px[0] })
        } finally {
            instrumentation.runOnMainSync { runCatching { presentation?.dismiss() } }
            vd.release(); imageReader.close(); frameThread.quitSafely()
        }
    }

    /** Read the RGBA_8888 image into an ARGB IntArray (handling rowStride/pixelStride) for a content check. */
    private fun readPixels(img: Image): IntArray {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = IntArray(img.width * img.height)
        for (y in 0 until img.height) {
            var rowStart = y * rowStride
            for (x in 0 until img.width) {
                val i = rowStart + x * pixelStride
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                val a = buf.get(i + 3).toInt() and 0xFF
                out[y * img.width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun waitUntil(what: String, deadlineMs: Long = 6_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + deadlineMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return
            SystemClock.sleep(20)
        }
        log("timed out waiting for: $what")
    }
}
