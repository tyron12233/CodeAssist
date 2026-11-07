package dev.ide.android.preview

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    /** [System.nanoTime] of the composition's first draw into the window, 0 until then. A frame queued after it
     *  carries what the composable drew, even when that is a few glyphs on transparent (which [hasContent]'s
     *  sparse sampling misses: a wrap-content `Text` touches none of its 64 points). */
    @Volatile private var firstDrawNanos = 0L
    private val frames = AtomicInteger(0)
    private val owner = OffscreenOwner()
    private val virtualDisplay = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
        "ca-compose-preview", width, height, densityDpi, imageReader.surface,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
    )

    @Volatile private var presentation: Presentation? = null
    @Volatile private var gestureDownTime = 0L

    // Frame pacing (frame thread only). The composition draws at the display rate, and a never-ending animation
    // (material3's wavy progress indicators, an infinite transition) draws forever: delivering every frame made
    // the IDE redraw its whole window 60 times a second and, on the CPU path, moved ~7.5 MB per frame through
    // disk. Frames are delivered at most every [minIntervalMs]; one that arrives sooner is HELD (replacing any
    // older held one) and delivered when its slot comes, so the last frame of an animation always arrives.
    @Volatile private var lastDeliveredAt = 0L
    private var held: Image? = null
    private var heldScheduled = false
    private val flushHeld = Runnable {
        heldScheduled = false
        val img = held ?: return@Runnable
        held = null
        runCatching { img.use { deliver(it) } }
    }

    /** Uptime until which frames go out at the full rate: an input gesture should track the finger. */
    @Volatile private var boostUntil = 0L

    /** Input: run (and deliver) frames at the full rate for a moment, even while paused (the tap's effect shows). */
    private fun noteInput() {
        boostUntil = SystemClock.uptimeMillis() + INPUT_BOOST_MS
        clock?.boostUntilNanos = System.nanoTime() + INPUT_BOOST_MS * 1_000_000L
        mainHandler.post { if (!closed.get()) clock?.allowFrames(INPUT_FRAMES_WHILE_PAUSED) }
    }

    private fun minIntervalMs(now: Long): Long {
        val zeroCopy = hwMode && onHardwareFrame != null
        val boosted = now < boostUntil
        return when {
            zeroCopy -> if (boosted) 0L else HW_FRAME_INTERVAL_MS
            else -> if (boosted) CPU_BOOSTED_FRAME_INTERVAL_MS else CPU_FRAME_INTERVAL_MS
        }
    }

    init {
        imageReader.setOnImageAvailableListener({ reader ->
          // Never let a single bad frame take down the frame thread (an invalidated reader throwing
          // IllegalStateException, a short/padded plane, …) — a dropped frame is the right degradation.
          runCatching {
            // acquireLatestImage drops any queued older frames, so a slow consumer coalesces to the newest frame
            // instead of falling behind — the preview shows "live", not a lagging backlog. With a held frame that
            // is at most 3 images acquired at once (held + latest + the one it probes), within maxImages.
            val img = reader.acquireLatestImage() ?: return@runCatching
            var keep = false
            try {
                // Drop pre-content frames: a freshly-shown Presentation delivers its window buffer BEFORE the
                // composition draws — a fully-transparent frame (the "black flash" on open). Once content has been
                // seen, stop sampling (latch) so the zero-copy path never touches the pixels again.
                if (!contentSeen) {
                    val drawn = firstDrawNanos
                    val afterDraw = drawn != 0L && (img.timestamp == 0L || img.timestamp >= drawn)
                    if (!afterDraw && !hasContent(img)) return@runCatching
                    contentSeen = true
                }
                val now = SystemClock.uptimeMillis()
                val wait = lastDeliveredAt + minIntervalMs(now) - now
                if (wait <= 0 && held == null) {
                    deliver(img)
                } else {
                    held?.close()
                    held = img
                    keep = true
                    if (!heldScheduled) {
                        heldScheduled = true
                        frameHandler.postDelayed(flushHeld, wait.coerceAtLeast(1L))
                    }
                }
            } finally {
                if (!keep) img.close()
            }
          }
        }, frameHandler)
    }

    /** Hand [img] to the consumer (frame thread); [img] is closed by the caller. */
    private fun deliver(img: Image) {
        lastDeliveredAt = SystemClock.uptimeMillis()
        val hwCb = onHardwareFrame
        if (hwMode && hwCb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val hb = img.hardwareBuffer ?: return
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

    // The composition runs on its OWN Recomposer, driven by [clock] (a 30 fps cap + pause) instead of the window
    // recomposer Compose would create, which ticks at the display rate. Created in [start] on the main thread.
    @Volatile private var clock: PreviewFrameClock? = null
    private var recomposerScope: CoroutineScope? = null
    private var recomposer: Recomposer? = null
    @Volatile private var pausedRequested = false

    /**
     * Freeze ([paused] = true) or resume the composition's animations. Paused, the clock lets a couple more frames
     * through and then stops, so an endless animation stops recomposing and drawing and nothing streams; a preview
     * paused before its first frame (or right after an edit) still shows it.
     */
    fun setPaused(paused: Boolean) {
        pausedRequested = paused
        mainHandler.post { if (!closed.get()) clock?.setPaused(paused) }
    }

    /** While paused, let the composition run a couple of frames (an edit, a resize, input), then freeze again. */
    fun renderOneFrame() {
        mainHandler.post { if (!closed.get()) clock?.allowFrames() }
    }

    /**
     * Where the display renders: [output] (a Surface the IDE owns, e.g. its preview pane's TextureView, so frames go
     * straight to the screen with no copy, Bitmap or recomposition on the IDE side), or this surface's own
     * [ImageReader] when null (frames reach [onFrame]/[onHardwareFrame]). The composition is redrawn into the new
     * target right away, paused or not. The caller keeps ownership of [output]: detach (null) before releasing it.
     */
    fun setOutput(output: Surface?) {
        mainHandler.post {
            if (closed.get()) return@post
            runCatching { virtualDisplay.surface = output ?: imageReader.surface }
            composeView?.invalidate()
        }
    }

    /** Set the streaming [content] (on the main thread) and keep the composition alive; frames flow to [onFrame]. */
    fun start(content: @Composable () -> Unit) {
        runOnMain {
            owner.resume()
            val p = Presentation(context, virtualDisplay.display)
            val ui = AndroidUiDispatcher.Main
            val frameClock = PreviewFrameClock(ui[MonotonicFrameClock]!!).also { it.setPaused(pausedRequested) }
            val ctx = ui + frameClock
            val r = Recomposer(ctx)
            val scope = CoroutineScope(ctx)
            scope.launch(start = CoroutineStart.UNDISPATCHED) { r.runRecomposeAndApplyChanges() }
            clock = frameClock
            recomposer = r
            recomposerScope = scope
            val view = ComposeView(p.context).apply {
                setParentCompositionContext(r)
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                // `BackHandler`/`PredictiveBackHandler` (material3's ModalDrawerSheet, BottomSheet, SearchBar, …)
                // require an OnBackPressedDispatcherOwner or a NavigationEventDispatcherOwner and throw without
                // one: an Activity supplies it; this Presentation has none. Android Studio's ComposeViewAdapter
                // provides a fake owner for exactly this reason; JetNews's AppDrawer preview blanked without it.
                setViewTreeOnBackPressedDispatcherOwner(owner)
                setContent(content)
            }
            composeView = view
            armFirstDraw(view)
            p.setContentView(view)
            // A Presentation is a Dialog: its window defaults to WRAP_CONTENT + centre gravity, so the ComposeView
            // can end up inset/centred within the VirtualDisplay rather than filling it from (0,0). That put a gap
            // at the top of the captured frame AND offset injected touches (the render is captured in DISPLAY px
            // but events dispatch into the WINDOW, whose origin was shifted) — taps landed above where you touched.
            // Pin the window to fill the display, top-left, no dialog inset/dim, so both share one (0,0) origin.
            p.window?.apply {
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.TOP or Gravity.START)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                decorView.setPadding(0, 0, 0, 0)
                // A Dialog's window carries the theme's opaque dialog background, which the frame captured: every
                // composable that draws no background of its own came out on the dark dialog colour, painting
                // over the card the IDE draws behind the frame (white, or the @Preview's showBackground colour),
                // so the starter template's "Hello, Compose!" read as dark text on dark grey. Transparent, the
                // frame carries only what the composable drew and the card shows through, as in Android Studio.
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            p.show()
            presentation = p
        }
    }

    @Volatile private var composeView: ComposeView? = null

    /** Mark the composition's next draw: frames rendered from then on are content, whatever they hold. The
     *  listener is removed on the next loop turn, since a draw listener may not remove itself while dispatched. */
    private fun armFirstDraw(view: ComposeView) {
        val firstDraw = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (firstDrawNanos == 0L) {
                    firstDrawNanos = System.nanoTime()
                    mainHandler.post { runCatching { view.viewTreeObserver.removeOnDrawListener(this) } }
                }
            }
        }
        view.viewTreeObserver.addOnDrawListener(firstDraw)
    }

    /**
     * Hold frames back again until the composition next draws (main thread). For content that arrives after
     * [start] (a session opened before its program was ready): what was drawn so far is the empty composition,
     * which must not reach the IDE as the preview's first frame.
     */
    fun rearmContentGate() {
        contentSeen = false
        firstDrawNanos = 0L
        composeView?.let { armFirstDraw(it) }
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
        noteInput()
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
        noteInput()
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
        runOnMain {
            runCatching { presentation?.dismiss() }
            runCatching { recomposer?.cancel() }
            runCatching { recomposerScope?.cancel() }
        }
        // Stop the producer, then detach the listener so no new frame callback is queued.
        runCatching { virtualDisplay.release() }
        runCatching { imageReader.setOnImageAvailableListener(null, null) }
        // Close the reader ON the frame thread. imageReader.close() unmaps the plane buffers; running it from any
        // other thread (this is called on the service's binder/main threads — session evict/close, dimension
        // change, onDestroy) can free a buffer while onImageAvailable → readFrameBytes is mid-memcpy on the frame
        // thread — the native SIGSEGV (SetByteArrayRegion/memcpy over an unmapped page) this fixes. Handler
        // messages are serial, so the posted close runs only AFTER any in-flight frame read finishes, and
        // quitSafely still delivers this already-due message before the looper exits.
        frameHandler.post {
            frameHandler.removeCallbacks(flushHeld)
            held?.let { runCatching { it.close() } }
            held = null
            runCatching { imageReader.close() }
        }
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

    /** A minimal RESUMED owner so `ComposeView` finds its ViewTree lifecycle / savedstate / viewmodel / back-
     *  dispatcher owners. The back dispatcher never fires (nothing presses Back on a virtual display); it only
     *  has to EXIST so back-handling composables compose. */
    private class OffscreenOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedState = SavedStateRegistryController.create(this)
        private val backDispatcher = OnBackPressedDispatcher()
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher
        private var restored = false
        fun resume() {
            if (!restored) { savedState.performRestore(null); restored = true }
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    companion object {
        /** Zero-copy delivery cap (~30 fps): smooth enough to watch an animation, half the IDE redraws of 60. */
        const val HW_FRAME_INTERVAL_MS = 33L
        /** CPU path (API < 29, or a GPU reader that couldn't be created): every frame is a full RGBA copy written
         *  to disk and read back, so it is held to ~10 fps (15 while the user is interacting). */
        const val CPU_FRAME_INTERVAL_MS = 100L
        const val CPU_BOOSTED_FRAME_INTERVAL_MS = 66L
        /** How long after an input event frames go out at the boosted rate. */
        const val INPUT_BOOST_MS = 500L
        /** Frames an input event may run while paused: enough for a click's state change and a short ripple. */
        const val INPUT_FRAMES_WHILE_PAUSED = 15
    }
}
