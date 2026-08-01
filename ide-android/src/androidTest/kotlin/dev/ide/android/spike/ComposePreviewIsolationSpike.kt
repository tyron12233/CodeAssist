package dev.ide.android.spike

import android.app.Presentation
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose-preview process-isolation **Phase 0 spike** (the gate for `docs/compose-preview-isolation.md`): prove an
 * off-screen live Compose runtime works from a non-window context — the single riskiest assumption of the whole
 * initiative. A `ComposeView` runs inside a `Presentation` on an app-owned `VirtualDisplay` backed by an
 * `ImageReader`; we verify it (1) **recomposes off-screen** (frames land in the `ImageReader`) and (2) an
 * **injected `dispatchTouchEvent` reaches a `clickable`**. If green, `:preview` can host the real interpreted
 * Compose runtime off the IDE thread and stream frames + forward input (bitmap-streaming isolation is viable).
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ComposePreviewIsolationSpike
 *     adb logcat -d -s ComposeIsolationSpike
 */
@RunWith(AndroidJUnit4::class)
class ComposePreviewIsolationSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("ComposeIsolationSpike", m); println(m) }

    /** A minimal RESUMED owner so `ComposeView` finds its ViewTree lifecycle/savedstate/viewmodel owners. */
    private class SpikeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        fun resume() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    @Test
    fun offscreenComposeViewRecomposesAndReceivesInput() {
        val ctx = instrumentation.targetContext
        val w = 240
        val h = 240
        val densityDpi = 320

        val frameThread = HandlerThread("spike-frames").apply { start() }
        val frameHandler = Handler(frameThread.looper)
        val frames = AtomicInteger(0)
        val clicked = AtomicBoolean(false)

        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.use { frames.incrementAndGet() }
        }, frameHandler)

        val dm = ctx.getSystemService(DisplayManager::class.java)
        val virtualDisplay = dm.createVirtualDisplay(
            "ca-preview-spike", w, h, densityDpi, imageReader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )

        val owner = SpikeOwner()
        var presentation: Presentation? = null

        try {
            instrumentation.runOnMainSync {
                owner.resume()
                val p = Presentation(ctx, virtualDisplay.display)
                val composeView = ComposeView(p.context).apply {
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeViewModelStoreOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                    setContent {
                        var count by remember { mutableIntStateOf(0) }
                        BasicText(
                            "count=$count",
                            modifier = Modifier.fillMaxSize().clickable {
                                clicked.set(true)
                                count++ // a state write → recomposition → a new frame
                            },
                        )
                    }
                }
                p.setContentView(composeView)
                p.show()
                presentation = p
            }

            // (1) It renders off-screen: wait for at least one frame in the ImageReader.
            waitUntil("first frame") { frames.get() > 0 }
            val framesBeforeInput = frames.get()
            log("SPIKE: off-screen ComposeView produced $framesBeforeInput frame(s) before input")

            // (2) An injected touch reaches the clickable (and its state write drives another frame).
            instrumentation.runOnMainSync {
                val decor = presentation!!.window!!.decorView
                val t = SystemClock.uptimeMillis()
                val cx = w / 2f
                val cy = h / 2f
                val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
                val up = MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, cx, cy, 0)
                decor.dispatchTouchEvent(down)
                decor.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }

            waitUntil("click handled") { clicked.get() }
            waitUntil("frame after click") { frames.get() > framesBeforeInput }
            log("SPIKE: clicked=${clicked.get()}, frames after input=${frames.get()} (recomposed on the injected touch)")

            assertTrue("off-screen ComposeView never produced a frame", framesBeforeInput > 0)
            assertTrue("injected dispatchTouchEvent did not reach the clickable", clicked.get())
            assertTrue("the click's state write did not drive a new off-screen frame", frames.get() > framesBeforeInput)
        } finally {
            instrumentation.runOnMainSync { runCatching { presentation?.dismiss() } }
            virtualDisplay.release()
            imageReader.close()
            frameThread.quitSafely()
        }
    }

    private fun waitUntil(what: String, deadlineMs: Long = 5_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + deadlineMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return
            SystemClock.sleep(20)
        }
        log("SPIKE: timed out waiting for: $what")
    }
}
