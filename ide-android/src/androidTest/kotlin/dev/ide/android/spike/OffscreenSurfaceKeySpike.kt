package dev.ide.android.spike

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.preview.OffscreenComposeSurface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keyboard forwarding (key-event level): a forwarded [KeyEvent] must reach a real `onKeyEvent` handler in the
 * off-screen composition and drive a new frame. A full-screen focusable `Box` turns red→blue when it receives
 * Key.A; the surface focuses it, [OffscreenComposeSurface.dispatchKey] forwards an A key press, and we assert
 * the handler ran AND a blue frame was captured. This is the mechanism the AIDL `dispatchKey` → session → surface
 * chain drives for hardware keyboards / nav / shortcut keys (soft-keyboard TEXT is a separate IME bridge).
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.OffscreenSurfaceKeySpike
 *     adb logcat -d -s OffscreenKey
 */
@RunWith(AndroidJUnit4::class)
class OffscreenSurfaceKeySpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun log(m: String) { Log.i("OffscreenKey", m); println(m) }

    @Test
    fun forwardedKeyReachesHandlerAndRedraws() {
        val ctx = instrumentation.targetContext
        val w = 200
        val h = 200
        val surface = OffscreenComposeSurface(ctx, w, h, 320)
        val captured = AtomicReference<OffscreenComposeSurface.Frame?>(null)
        val keyed = AtomicBoolean(false)
        val color = mutableStateOf(Color.Red)
        surface.onFrame = { captured.set(it) }
        try {
            surface.start {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(
                    Modifier.fillMaxSize().background(color.value).focusRequester(focus).focusable()
                        .onKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.A) { keyed.set(true); color.value = Color.Blue; true } else false
                        },
                )
            }
            waitUntil("first (red) frame + focus") { isColor(captured.get(), red = true) }
            SystemClock.sleep(150) // let focus settle
            surface.dispatchKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)
            surface.dispatchKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0)

            waitUntil("keyed + blue frame") { keyed.get() && isColor(captured.get(), red = false) }
            val c = captured.get()?.argb(w / 2, h / 2) ?: 0
            log("OFFSCREEN-KEY: keyed=${keyed.get()}, center=ARGB(${(c ushr 24) and 0xFF},${(c ushr 16) and 0xFF},${(c ushr 8) and 0xFF},${c and 0xFF})")

            assertTrue("the forwarded key did not reach the onKeyEvent handler", keyed.get())
            assertTrue("the key's state write did not drive a blue frame off-screen", isColor(captured.get(), red = false))
        } finally {
            surface.close()
        }
    }

    private fun isColor(f: OffscreenComposeSurface.Frame?, red: Boolean): Boolean {
        if (f == null) return false
        val c = f.argb(f.width / 2, f.height / 2)
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
