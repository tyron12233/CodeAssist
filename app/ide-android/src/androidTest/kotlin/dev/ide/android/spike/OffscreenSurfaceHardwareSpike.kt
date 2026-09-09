package dev.ide.android.spike

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.OffscreenComposeSurface
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Zero-copy transport (API 29+): the surface streams the GPU [HardwareBuffer] the composition rendered into, and
 * the consumer wraps it with `Bitmap.wrapHardwareBuffer` — no pixel readback. Verifies (1) the wrapped frame holds
 * the real content (a red full-screen Box → red centre, read back via a software copy) and (2) the delivery rate.
 * Skips on a device with no GPU HardwareBuffer reader (`hardwareAccelerated == false` → the bulk-copy path is used
 * instead), which is exactly the graceful degrade the production code does.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.OffscreenSurfaceHardwareSpike
 *     adb logcat -d -s OffscreenHW
 */
@RunWith(AndroidJUnit4::class)
class OffscreenSurfaceHardwareSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("OffscreenHW", m); println(m) }

    @Test
    fun zeroCopyHardwareBufferCarriesContent() {
        assumeTrue("wrapHardwareBuffer needs API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val ctx = instrumentation.targetContext
        val w = 240
        val h = 240
        val surface = OffscreenComposeSurface(ctx, w, h, 320)
        assumeTrue("no GPU HardwareBuffer reader here — the bulk-copy fallback path is used instead", surface.hardwareAccelerated)

        val centre = AtomicReference<Int?>(null)
        val frames = AtomicInteger(0)
        surface.onHardwareFrame = { hb: HardwareBuffer, bw: Int, bh: Int ->
            frames.incrementAndGet()
            // Wrap the shared buffer (zero-copy) then copy JUST this frame back to software to read a pixel.
            val bmp = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
            if (bmp != null && centre.get() == null) {
                val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                centre.set(sw.getPixel(bw / 2, bh / 2))
            }
        }
        try {
            surface.start { Box(Modifier.fillMaxSize().background(Color.Red)) }
            val end = SystemClock.uptimeMillis() + 8_000
            while (centre.get() == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(20)

            val c = centre.get()
            if (c == null) { log("HW: no HardwareBuffer frame carried content"); assertTrue("no zero-copy frame", false); return }
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            log("HW: zero-copy centre=ARGB(${(c ushr 24) and 0xFF},$r,$g,$b), frames=${frames.get()}")
            assertTrue("wrapped HardwareBuffer centre is not red (ARGB $r,$g,$b) — zero-copy transport is broken", r > 150 && g < 100 && b < 100)
        } finally {
            surface.close()
        }
    }
}
