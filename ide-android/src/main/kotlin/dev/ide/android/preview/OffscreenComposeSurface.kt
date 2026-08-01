package dev.ide.android.preview

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * An off-screen live Compose surface: a `ComposeView` hosted in a [Presentation] on an app-owned
 * [android.hardware.display.VirtualDisplay] backed by an [ImageReader]. It gives arbitrary interpreted Compose
 * content a real `ViewRootImpl` + Choreographer (so recomposition, animation, and input all work) while its
 * frames land in the `ImageReader` instead of on any screen — the render primitive the `:preview` process uses
 * to render a Compose `@Preview` out of the IDE's own composition (de-risked by `ComposePreviewIsolationSpike`
 * and `ComposePreviewOffscreenRenderSpike`; see `docs/compose-preview-isolation.md`).
 *
 * Usable from a non-window (Service) [context]. The content is set on the main thread (its Choreographer drives
 * recomposition), so callers on a Binder thread block on [renderFirstFrame] while frames render on main. One
 * surface per live preview; [close] releases the display, reader, and frame thread.
 */
class OffscreenComposeSurface(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
) : AutoCloseable {

    /** A captured off-screen frame as ARGB_8888 pixels (row-major, [width] × [height]). */
    class Frame(val pixels: IntArray, val width: Int, val height: Int)

    private val frameThread = HandlerThread("ca-preview-frames").apply { start() }
    private val frameHandler = Handler(frameThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    private val latestPixels = AtomicReference<IntArray?>(null)
    private val frames = AtomicInteger(0)
    private val owner = OffscreenOwner()
    private val virtualDisplay = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
        "ca-compose-preview", width, height, densityDpi, imageReader.surface,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
    )

    @Volatile private var presentation: Presentation? = null

    init {
        imageReader.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.use { latestPixels.set(readPixels(it)); frames.incrementAndGet() }
        }, frameHandler)
    }

    /**
     * Compose [content] off-screen and block up to [timeoutMs] for its first frame. Returns that frame's pixels,
     * or null if none was produced in time. The `ComposeView` is created (and thus recomposes) on the main
     * thread; safe to call from a Binder/background thread.
     */
    fun renderFirstFrame(timeoutMs: Long = 6_000, content: @Composable () -> Unit): Frame? {
        val before = frames.get()
        runOnMain {
            owner.resume()
            val p = Presentation(context, virtualDisplay.display)
            val view = ComposeView(p.context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent(content)
            }
            p.setContentView(view)
            p.show()
            presentation = p
        }
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (frames.get() <= before && SystemClock.uptimeMillis() < end) SystemClock.sleep(16)
        val px = latestPixels.get() ?: return null
        return Frame(px, width, height)
    }

    override fun close() {
        runOnMain { runCatching { presentation?.dismiss() } }
        virtualDisplay.release()
        imageReader.close()
        frameThread.quitSafely()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val latch = CountDownLatch(1)
        mainHandler.post { try { block() } finally { latch.countDown() } }
        latch.await(6, TimeUnit.SECONDS)
    }

    /** Read the RGBA_8888 [img] into an ARGB IntArray, honoring the plane's row/pixel strides. */
    private fun readPixels(img: Image): IntArray {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = IntArray(img.width * img.height)
        for (y in 0 until img.height) {
            val rowStart = y * rowStride
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

    /** A minimal RESUMED owner so `ComposeView` finds its ViewTree lifecycle / savedstate / viewmodel owners. */
    private class OffscreenOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        private var restored = false
        fun resume() {
            if (!restored) { savedState.performRestore(null); restored = true }
            registry.currentState = Lifecycle.State.RESUMED
        }
    }
}
