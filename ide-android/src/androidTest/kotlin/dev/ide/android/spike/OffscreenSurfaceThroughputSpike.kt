package dev.ide.android.spike

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.OffscreenComposeSurface
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame-transport throughput: an animating full-size (822×1462) off-screen surface must sustain a high frame
 * rate through the readback path. The old per-pixel ARGB conversion held a ~20ms/frame JNI-critical lock (logged
 * "JNI critical lock held for 20ms on ca-preview-frames") and capped the rate; the bulk RGBA copy should clear
 * it. This renders a per-vsync-animating Box (continuous redraw) and reports the frames/sec delivered to onFrame.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.OffscreenSurfaceThroughputSpike
 *     adb logcat -d -s OffscreenThroughput ; adb logcat -d | grep 'JNI critical'
 */
@RunWith(AndroidJUnit4::class)
class OffscreenSurfaceThroughputSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("OffscreenThroughput", m); println(m) }

    @Test
    fun sustainsFrameRateAtFullSize() {
        val ctx = instrumentation.targetContext
        val surface = OffscreenComposeSurface(ctx, 822, 1462, 320)
        val count = AtomicInteger(0)
        surface.onFrame = { count.incrementAndGet() }
        try {
            surface.start {
                // Animate every vsync so the composition redraws continuously — a worst-case for the transport.
                var hue by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(Unit) {
                    while (true) withFrameNanos { hue = (hue + 3f) % 360f }
                }
                Box(Modifier.fillMaxSize().background(Color.hsv(hue, 0.8f, 0.9f)))
            }
            SystemClock.sleep(700) // warm up (first frame + animation spin-up)
            val start = count.get()
            val t0 = SystemClock.uptimeMillis()
            SystemClock.sleep(2_000)
            val frames = count.get() - start
            val ms = SystemClock.uptimeMillis() - t0
            val fps = frames * 1000.0 / ms
            log("THROUGHPUT: $frames frames in ${ms}ms = ${"%.1f".format(fps)} fps at 822x1462")

            assertTrue("no frames delivered — the surface never produced content", frames > 0)
            assertTrue("frame throughput too low (${"%.1f".format(fps)} fps) — transport is the bottleneck", fps >= 30.0)
        } finally {
            surface.close()
        }
    }
}
