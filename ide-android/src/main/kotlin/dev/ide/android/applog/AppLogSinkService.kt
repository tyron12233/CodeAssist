package dev.ide.android.applog

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import dev.ide.android.AppLogSinkRegistry
import dev.ide.core.AppLogWire

/**
 * The exported Binder service the log bridge injected into a running debug app binds to (resolved by the
 * [AppLogWire.SINK_ACTION] intent) and pushes its log frames over. A `LocalSocket` can't cross the
 * untrusted-app boundary — SELinux denies one untrusted app connecting to another's abstract socket
 * (`avc: denied { connectto }`) — so Binder is the sanctioned channel. Each [AppLogWire.TXN_SUBMIT]
 * transaction carries a `String[]` of wire payloads, routed to the active [dev.ide.android.AppLogChannelImpl]
 * via [AppLogSinkRegistry] (both live in the IDE process).
 *
 * Exported with no permission (the built app is signed with a different key, so a signature permission can't
 * gate it); safe because the channel drops every frame whose HELLO package isn't the currently-launched app —
 * a stray bind from another app contributes nothing. This matches the prior socket transport's trust model.
 */
class AppLogSinkService : Service() {

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == AppLogWire.TXN_SUBMIT) {
                data.enforceInterface(AppLogWire.BINDER_DESCRIPTOR)
                val frames = data.createStringArray()
                if (frames != null) AppLogSinkRegistry.active?.acceptFrames(frames.filterNotNull())
                return true // oneway transaction — no reply
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        AppLogSinkRegistry.active?.onClientDisconnected()
        return false
    }
}
