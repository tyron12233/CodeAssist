package dev.ide.android.preview

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
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
import java.util.concurrent.atomic.AtomicBoolean
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
 * The surface is **persistent + streaming**: [start] sets the content once and keeps the composition alive, and
 * every frame the composition draws (its first frame, an animation tick, or a recomposition after a
 * [runOnMain] state write) is delivered to [onFrame]. A static preview draws one frame then idles (Compose only
 * redraws on invalidation), so an idle preview streams nothing. Usable from a non-window (Service) [context];
 * content is created on the main thread (its Choreographer drives recomposition). One surface per live session;
 * [close] releases the display, reader, and frame thread.
 */
class OffscreenComposeSurface(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
) : AutoCloseable {

    /**
     * A captured off-screen frame as the raw RGBA_8888 bytes the `ImageReader` produced (row-major, tightly
     * packed, [width] × [height] × 4). Kept as bytes end-to-end: the producer bulk-copies the plane (no per-pixel
     * work — the old per-pixel ARGB conversion held a ~20ms/frame JNI-critical lock), the transport writes the
     * bytes as-is, and the consumer maps them straight into an ARGB_8888 `Bitmap` via `copyPixelsFromBuffer`
     * (Android's ARGB_8888 in-memory layout IS RGBA, so no conversion there either).
     */
    class Frame(val bytes: ByteArray, val width: Int, val height: Int) {
        /** The ARGB int of pixel ([x],[y]) — decoded from the raw RGBA bytes. For assertions/tests. */
        fun argb(x: Int, y: Int): Int {
            val i = (y * width + x) * 4
            val r = bytes[i].toInt() and 0xFF
            val g = bytes[i + 1].toInt() and 0xFF
            val b = bytes[i + 2].toInt() and 0xFF
            val a = bytes[i + 3].toInt() and 0xFF
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Invoked (on the frame thread) with the CPU bytes for every frame — the API 26-28 fallback path. Set before
     *  [start]. Ignored when [onHardwareFrame] is set. */
    @Volatile var onFrame: ((Frame) -> Unit)? = null

    /** Invoked (on the frame thread) with the frame's shared [HardwareBuffer] — the zero-copy path (only when
     *  [hardwareAccelerated]). The buffer is valid only for the duration of the call (closed right after), so a
     *  consumer must wrap/dup it synchronously (a oneway Binder send dups the fd before returning). Set before
     *  [start]; takes precedence over [onFrame]. */
    @Volatile var onHardwareFrame: ((HardwareBuffer, Int, Int) -> Unit)? = null

    private val frameThread = HandlerThread("ca-preview-frames").apply { start() }
    private val frameHandler = Handler(frameThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)

    // GPU-backed ImageReader when the platform supports zero-copy (API 29+ for the usage overload +
    // Bitmap.wrapHardwareBuffer); else a plain CPU reader. USAGE_CPU_READ_RARELY keeps the plane sampleable for the
    // pre-content blank check. Creation can fail on quirky GPUs/emulators — fall back to the CPU reader.
    private var hwMode = false
    private val imageReader: ImageReader = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                ImageReader.newInstance(
                    width, height, PixelFormat.RGBA_8888, 3,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_RARELY,
                )
            }.getOrNull()?.let { hwMode = true; return@run it }
        }
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    }

    /** Whether the surface can deliver zero-copy [HardwareBuffer] frames (API 29+ and the GPU reader was created). */
    val hardwareAccelerated: Boolean get() = hwMode

    private val latestBytes = AtomicReference<ByteArray?>(null)
    @Volatile private var contentSeen = false
    private val frames = AtomicInteger(0)
    private val owner = OffscreenOwner()
    private val virtualDisplay = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
        "ca-compose-preview", width, height, densityDpi, imageReader.surface,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
    )

    @Volatile private var presentation: Presentation? = null
    @Volatile private var gestureDownTime = 0L

    init {
        imageReader.setOnImageAvailableListener({ reader ->
          // Never let a single bad frame take down the frame thread (an invalidated reader throwing
          // IllegalStateException, a short/padded plane, …) — a dropped frame is the right degradation.
          runCatching {
            // acquireLatestImage drops any queued older frames, so a slow consumer coalesces to the newest frame
            // instead of falling behind — the preview shows "live", not a lagging backlog.
            reader.acquireLatestImage()?.use { img ->
                // Drop pre-content frames: a freshly-shown Presentation delivers its window buffer BEFORE the
                // composition draws — a fully-transparent frame (the "black flash" on open). Once content has been
                // seen, stop sampling (latch) so the zero-copy path never touches the pixels again.
                if (!contentSeen) {
                    if (!hasContent(img)) return@use
                    contentSeen = true
                }
                val hwCb = onHardwareFrame
                if (hwMode && hwCb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val hb = img.hardwareBuffer ?: return@use
                    frames.incrementAndGet()
                    hb.use { hb ->
                        hwCb(hb, img.width, img.height)
                    }
                } else {
                    val frame = Frame(readFrameBytes(img), img.width, img.height)
                    latestBytes.set(frame.bytes)
                    frames.incrementAndGet()
                    runCatching { onFrame?.invoke(frame) }
                }
            }
          }
        }, frameHandler)
    }

    /** Set the streaming [content] (on the main thread) and keep the composition alive; frames flow to [onFrame]. */
    fun start(content: @Composable () -> Unit) {
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
    }

    /**
     * Compose [content] and block up to [timeoutMs] for its first frame. Convenience for one-shot renders (the
     * client's `renderOnce`); returns that frame's pixels, or null if none was produced in time.
     */
    fun renderFirstFrame(timeoutMs: Long = 6_000, content: @Composable () -> Unit): Frame? {
        val before = frames.get()
        start(content)
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (frames.get() <= before && SystemClock.uptimeMillis() < end) SystemClock.sleep(16)
        val bytes = latestBytes.get() ?: return null
        return Frame(bytes, width, height)
    }

    /**
     * Forward a pointer event into the composition: rebuild a [MotionEvent] and dispatch it to the Presentation's
     * decor view (the same path a real touch on that display takes), so `clickable`/`scrollable`/gestures fire.
     * Posted to the main thread WITHOUT blocking (a MOVE stream must not stall the caller) and in order (Handler
     * FIFO). [x]/[y] are in this surface's pixel space. Times use the local uptime clock; the gesture's downTime is
     * tracked from its ACTION_DOWN so MOVE/UP belong to the same gesture.
     */
    fun dispatchTouch(action: Int, x: Float, y: Float, pointerId: Int) {
        mainHandler.post {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) gestureDownTime = now
            val downTime = if (gestureDownTime == 0L) now else gestureDownTime
            val ev = MotionEvent.obtain(downTime, now, action, x, y, 0)
            try {
                presentation?.window?.decorView?.dispatchTouchEvent(ev)
            } finally {
                ev.recycle()
            }
        }
    }

    /** Forward a key event: rebuild a [KeyEvent] and dispatch it to the Presentation's decor view (posted to main,
     *  non-blocking). Reaches hardware-keyboard handling + `onKeyEvent`/focus/nav keys in the composition. */
    fun dispatchKey(action: Int, keyCode: Int, metaState: Int) {
        mainHandler.post {
            val now = SystemClock.uptimeMillis()
            val ev = KeyEvent(now, now, action, keyCode, 0, metaState)
            presentation?.window?.decorView?.dispatchKeyEvent(ev)
        }
    }

    /** Run [block] on the main thread (where the composition lives), blocking the caller. Use for state writes
     *  that drive a live-edit recomposition. */
    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val latch = CountDownLatch(1)
        mainHandler.post { try { block() } finally { latch.countDown() } }
        latch.await(6, TimeUnit.SECONDS)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return   // idempotent: evict + onDestroy may both close a session
        onFrame = null
        onHardwareFrame = null
        runOnMain { runCatching { presentation?.dismiss() } }
        // Stop the producer, then detach the listener so no new frame callback is queued.
        runCatching { virtualDisplay.release() }
        runCatching { imageReader.setOnImageAvailableListener(null, null) }
        // Close the reader ON the frame thread. imageReader.close() unmaps the plane buffers; running it from any
        // other thread (this is called on the service's binder/main threads — session evict/close, dimension
        // change, onDestroy) can free a buffer while onImageAvailable → readFrameBytes is mid-memcpy on the frame
        // thread — the native SIGSEGV (SetByteArrayRegion/memcpy over an unmapped page) this fixes. Handler
        // messages are serial, so the posted close runs only AFTER any in-flight frame read finishes, and
        // quitSafely still delivers this already-due message before the looper exits.
        frameHandler.post { runCatching { imageReader.close() } }
        frameThread.quitSafely()
    }

    /** Post [block] to the main thread WITHOUT blocking the caller (FIFO order). For fire-and-forget work like
     *  a live-edit program-state write, where blocking a Binder thread on the (possibly-busy) render thread is
     *  what made `update` a 1s stall. */
    fun postToMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    /**
     * Copy the RGBA_8888 [img] into a tightly-packed byte array in ONE bulk transfer per row (the common case is a
     * single whole-buffer copy). No per-pixel work: the old ARGB-int conversion loop held a JNI-critical lock ~20ms
     * per frame, blowing the 16ms budget. RGBA_8888 always has a 4-byte pixel stride, so only row padding
     * (rowStride > width*4) needs a per-row copy; an unpadded buffer is a single `get`.
     */
    private fun readFrameBytes(img: Image): ByteArray {
        val plane = img.planes[0]
        val buf = plane.buffer
        val w = img.width
        val h = img.height
        val rowStride = plane.rowStride
        val out = ByteArray(w * h * 4)
        val base = buf.position()
        if (rowStride == w * 4) {
            buf.position(base)
            buf.get(out, 0, out.size)
        } else {
            var o = 0
            for (y in 0 until h) {
                buf.position(base + y * rowStride)
                buf.get(out, o, w * 4)
                o += w * 4
            }
        }
        return out
    }

    /** Cheap "did the composition draw anything" test: sample an 8×8 grid of pixels for any non-transparent one,
     *  instead of scanning all ~1.2M. A fully-transparent frame is the pre-content Presentation window (skipped). */
    private fun hasContent(img: Image): Boolean {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val base = buf.position()
        val steps = 8
        for (iy in 0 until steps) {
            val y = if (steps == 1) 0 else iy * (img.height - 1) / (steps - 1)
            for (ix in 0 until steps) {
                val x = if (steps == 1) 0 else ix * (img.width - 1) / (steps - 1)
                val i = base + y * rowStride + x * pixelStride
                if (buf.get(i).toInt() != 0 || buf.get(i + 1).toInt() != 0 ||
                    buf.get(i + 2).toInt() != 0 || buf.get(i + 3).toInt() != 0
                ) return true
            }
        }
        return false
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
