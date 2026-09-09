package dev.ide.android.spike

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.OffscreenComposeSurface
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic: does [OffscreenComposeSurface] actually CAPTURE opaque content colour, or does it come back black?
 * Renders a full-screen red `Box` (real Compose — no interpreter) and checks the captured centre pixel is red.
 * Decides whether a "black preview" is a capture/compositing problem (centre is black) or a content/sizing one
 * (centre is red, so the surface works and the interpreted content/theme/size is at fault).
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.OffscreenSurfaceColorSpike
 *     adb logcat -d -s OffscreenColor
 */
@RunWith(AndroidJUnit4::class)
class OffscreenSurfaceColorSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("OffscreenColor", m); println(m) }

    @Test
    fun capturesOpaqueContentColourNotBlack() {
        val ctx = instrumentation.targetContext
        val w = 200
        val h = 200
        val surface = OffscreenComposeSurface(ctx, w, h, 320)
        val captured = AtomicReference<OffscreenComposeSurface.Frame?>(null)
        val frameCount = java.util.concurrent.atomic.AtomicInteger(0)
        surface.onFrame = { captured.set(it); frameCount.incrementAndGet() }
        try {
            surface.start { Box(Modifier.fillMaxSize().background(Color.Red)) }
            // The surface skips pre-content (fully-transparent) frames, so the FIRST delivered frame must already
            // be content — this doubles as the regression guard for the black-flash fix: if a blank frame leaked
            // through, the centre would be ARGB(0,0,0,0) and this fails.
            val end = SystemClock.uptimeMillis() + 8_000
            while (captured.get() == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(20)

            val frame = captured.get()
            if (frame == null) { log("OFFSCREEN-COLOR: NO FRAME captured"); assertTrue("no frame captured off-screen", false); return }

            val c = frame.argb(w / 2, h / 2)
            val a = (c ushr 24) and 0xFF
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            log("OFFSCREEN-COLOR: center=ARGB($a,$r,$g,$b), frames=${frameCount.get()}")

            assertTrue("center pixel is NOT red (ARGB $a,$r,$g,$b) — the off-screen surface captured black, not the content", r > 150 && g < 100 && b < 100)
        } finally {
            surface.close()
        }
    }
}
