package dev.ide.android.spike

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.OffscreenComposeSurface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose-preview isolation **Phase 3** (input forwarding), at the surface level: a forwarded pointer event must
 * reach a real `clickable` in the off-screen composition and drive a new frame. Renders a full-screen `Box` that
 * starts red and turns blue on click (real Compose — no interpreter), dispatches a tap via
 * [OffscreenComposeSurface.dispatchTouch], and asserts the click handler ran AND a blue frame was captured. This
 * is the mechanism the AIDL `dispatchInput` → session → surface chain drives end-to-end.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.OffscreenSurfaceInputSpike
 *     adb logcat -d -s OffscreenInput
 */
@RunWith(AndroidJUnit4::class)
class OffscreenSurfaceInputSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("OffscreenInput", m); println(m) }

    @Test
    fun forwardedTouchReachesClickableAndRedraws() {
        val ctx = instrumentation.targetContext
        val w = 200
        val h = 200
        val surface = OffscreenComposeSurface(ctx, w, h, 320)
        val captured = AtomicReference<OffscreenComposeSurface.Frame?>(null)
        val clicked = AtomicBoolean(false)
        val color = mutableStateOf(Color.Red)
        surface.onFrame = { captured.set(it) }
        try {
            surface.start {
                Box(Modifier.fillMaxSize().background(color.value).clickable { clicked.set(true); color.value = Color.Blue })
            }
            // Wait for the initial red frame, then forward a tap at the centre.
            waitUntil("first (red) frame") { isColor(captured.get(), red = true) }
            surface.dispatchTouch(MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
            surface.dispatchTouch(MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)

            waitUntil("clicked + blue frame") { clicked.get() && isColor(captured.get(), red = false) }
            val c = captured.get()?.pixels?.get((h / 2) * w + (w / 2)) ?: 0
            log("OFFSCREEN-INPUT: clicked=${clicked.get()}, center=ARGB(${(c ushr 24) and 0xFF},${(c ushr 16) and 0xFF},${(c ushr 8) and 0xFF},${c and 0xFF})")

            assertTrue("the forwarded tap did not reach the clickable", clicked.get())
            assertTrue("the click's state write did not drive a blue frame off-screen", isColor(captured.get(), red = false))
        } finally {
            surface.close()
        }
    }

    /** Centre pixel is red (else blue) — the two states of the test content. */
    private fun isColor(f: OffscreenComposeSurface.Frame?, red: Boolean): Boolean {
        val c = f?.pixels?.getOrNull(f.width / 2 + (f.height / 2) * f.width) ?: return false
        val r = (c ushr 16) and 0xFF
        val b = c and 0xFF
        return if (red) r > 150 && b < 100 else b > 150 && r < 100
    }

    private fun waitUntil(what: String, deadlineMs: Long = 8_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + deadlineMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return
            SystemClock.sleep(20)
        }
        log("timed out waiting for: $what")
    }
}
