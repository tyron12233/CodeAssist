package dev.ide.android.spike

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.OffscreenComposeSurface
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Touch POSITION accuracy for the off-screen preview surface — the follow-up to [OffscreenSurfaceInputSpike],
 * which only taps the centre of a full-screen box (an origin/scale offset slips through). This lays out four
 * quadrants and taps the centre of each, asserting the tap reaches the RIGHT quadrant.
 *
 * If this FAILS (a top tap fires a bottom quadrant, etc.), the `:preview` window/injection is offset — e.g. a
 * `Presentation` whose window doesn't fill the VirtualDisplay from (0,0), so injected display coords land in the
 * wrong place ("taps land above where you touched"). If it PASSES, the surface injection is correct and any
 * mis-routing is IDE-side (the display→canvas mapping in `RemoteComposePreview`).
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.OffscreenSurfaceTouchAccuracySpike
 *     adb logcat -d -s OffscreenTouchAccuracy
 */
@RunWith(AndroidJUnit4::class)
class OffscreenSurfaceTouchAccuracySpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("OffscreenTouchAccuracy", m); println(m) }

    @Test
    fun forwardedTapsReachTheQuadrantTheyLandIn() {
        val ctx = instrumentation.targetContext
        val w = 240
        val h = 400
        val surface = OffscreenComposeSurface(ctx, w, h, 320)
        val hit = AtomicReference("")
        val drawn = AtomicReference(false)
        surface.onFrame = { drawn.set(true) }
        try {
            surface.start {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Box(Modifier.weight(1f).fillMaxSize().background(Color.Red).clickable { hit.set("TL") })
                        Box(Modifier.weight(1f).fillMaxSize().background(Color.Green).clickable { hit.set("TR") })
                    }
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Box(Modifier.weight(1f).fillMaxSize().background(Color.Blue).clickable { hit.set("BL") })
                        Box(Modifier.weight(1f).fillMaxSize().background(Color.Yellow).clickable { hit.set("BR") })
                    }
                }
            }
            waitUntil("first frame") { drawn.get() }

            // Tap the centre of each quadrant (surface px) and record which clickable fired.
            val cases = listOf(
                Triple(w * 0.25f, h * 0.25f, "TL"),
                Triple(w * 0.75f, h * 0.25f, "TR"),
                Triple(w * 0.25f, h * 0.75f, "BL"),
                Triple(w * 0.75f, h * 0.75f, "BR"),
            )
            for ((x, y, expected) in cases) {
                hit.set("")
                surface.dispatchTouch(MotionEvent.ACTION_DOWN, x, y, 0)
                surface.dispatchTouch(MotionEvent.ACTION_UP, x, y, 0)
                waitUntil("tap ($x,$y) → clickable") { hit.get().isNotEmpty() }
                log("TAP ($x,$y) expected=$expected got=${hit.get()}")
                assertEquals("tap at ($x,$y) landed in the wrong quadrant (surface window not filling from 0,0?)", expected, hit.get())
            }
        } finally {
            surface.close()
        }
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
