package dev.ide.android

import android.os.Handler
import android.os.Looper

/**
 * Holds activity launches that would otherwise open on top of, or under, a full-screen ad: the install
 * confirmation and the launch of the just-built app after a Run. [AndroidAdHost] marks the ad shown and closed;
 * whatever asked to run meanwhile runs once it closes, in order, on the main thread.
 *
 * Process-local. Ads only ever show in the UI process, so in `:build` the gate is always open and [runWhenClear]
 * runs at once; the installer there forwards its launches to the UI process, which holds them here.
 */
object FullScreenAdGate {
    /** The longest a launch is held, in case the ad never reports closing. */
    private const val MAX_HOLD_MS = 90_000L

    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<() -> Unit>()
    private var showing = false

    /** A full-screen ad is on screen. */
    fun adShown() = synchronized(this) { showing = true }

    /** The full-screen ad closed (or failed to show): run what was held. */
    fun adClosed() {
        synchronized(this) { showing = false }
        release()
    }

    /** Run [action] now, or once the full-screen ad on screen closes. */
    fun runWhenClear(action: () -> Unit) {
        synchronized(this) {
            if (showing) {
                pending += action
                main.postDelayed(::release, MAX_HOLD_MS)
                return
            }
        }
        action()
    }

    private fun release() {
        val held = synchronized(this) { pending.toList().also { pending.clear() } }
        for (action in held) main.post { runCatching(action) }
    }
}
