package dev.codeassist.dart

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import androidx.annotation.RequiresApi
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterJNI
import io.flutter.embedding.engine.dart.DartExecutor

/**
 * Flutter engines for the editor's preview pane, drawn INTO the IDE's window.
 *
 * The pane is a `SurfaceView` in CodeAssist; it sends its host token, display and size, and gets back a
 * `SurfacePackage`: a [FlutterView] rendered here, in this plugin's process, by a [SurfaceControlViewHost],
 * which the pane attaches to its surface. The pane forwards its touches ([INPUT]). Android 11 and later.
 *
 * Each engine starts on the player's stub program, and the reply carries its Dart VM service URI and isolate,
 * through which the IDE runs the gallery in it and hot-reloads it. That is why only CodeAssist may ask: a VM
 * service runs any code it is given, as this app. A message's sender is checked by its uid, which Binder
 * supplies and the sender cannot choose.
 *
 * Runs in its own process (`:preview`), apart from the player: an engine exiting (`ext.flutter.exit`) ends its
 * process, and the Run's player and the pane must not take each other down, or share a VM with its prints.
 *
 * Protocol (a [Messenger]; `arg1` is the pane's own id throughout):
 * - [OPEN]: `data` {[KEY_HOST_TOKEN], [KEY_DISPLAY], [KEY_WIDTH], [KEY_HEIGHT]}, `replyTo` set. Answered by
 *   [OPENED] {[KEY_SURFACE], [KEY_URI], [KEY_ISOLATE], [KEY_REUSED]} or [FAILED] {[KEY_REASON]}. An OPEN for an
 *   id whose engine is running hands that engine to the new surface ([KEY_REUSED]) rather than starting one.
 * - [RESIZE]: `data` {[KEY_WIDTH], [KEY_HEIGHT]}.
 * - [INPUT]: `data` {[KEY_EVENT]}, a MotionEvent in the view's coordinates.
 * - [CLOSE]: the engine is destroyed. Unbinding closes every engine the client opened.
 */
class FlutterPreviewService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val engines = HashMap<Int, Embedded>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> handle(msg); true })

    /** Whether a pane is bound: the process ends itself shortly after the last one unbinds. */
    private var clients = false

    override fun onBind(intent: Intent): IBinder {
        clients = true
        return messenger.binder
    }

    override fun onRebind(intent: Intent) {
        clients = true
    }

    /**
     * The last pane let go (its preview parked long enough, or the IDE went). Its engines go, and so does this
     * process soon after: without an engine it still holds the Flutter library and a Dart VM, some 80 MB, for
     * nothing. A pane that binds again in the meantime keeps it.
     */
    override fun onUnbind(intent: Intent): Boolean {
        clients = false
        closeAll()
        main.postDelayed({ if (!clients && engines.isEmpty()) Process.killProcess(Process.myPid()) }, 2_000)
        return true
    }

    override fun onDestroy() {
        closeAll()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val caller = msg.sendingUid
        if (!trusted(caller)) {
            Log.w(TAG, "refused a preview for uid $caller")
            reply(msg.replyTo, FAILED, msg.arg1, Bundle().apply { putString(KEY_REASON, "Not CodeAssist.") })
            return
        }
        when (msg.what) {
            OPEN -> if (Build.VERSION.SDK_INT >= 30) open(msg) else {
                reply(msg.replyTo, FAILED, msg.arg1, Bundle().apply { putString(KEY_REASON, "The preview pane needs Android 11 or later.") })
            }
            RESIZE -> if (Build.VERSION.SDK_INT >= 30) engines[msg.arg1]?.resize(msg.data.getInt(KEY_WIDTH), msg.data.getInt(KEY_HEIGHT))
            CLOSE -> engines.remove(msg.arg1)?.close()
            INPUT -> if (Build.VERSION.SDK_INT >= 30) {
                @Suppress("DEPRECATION")
                val event = msg.data.apply { classLoader = MotionEvent::class.java.classLoader }.getParcelable<MotionEvent>(KEY_EVENT)
                if (event != null) {
                    engines[msg.arg1]?.input(event)
                    event.recycle()
                }
            }
        }
    }

    @RequiresApi(30)
    private fun open(msg: Message) {
        val id = msg.arg1
        val replyTo = msg.replyTo ?: return
        val data = msg.data
        val failed = { reason: String -> reply(replyTo, FAILED, id, Bundle().apply { putString(KEY_REASON, reason) }) }
        val token = data.getBinder(KEY_HOST_TOKEN) ?: return failed("No host window.")
        val display = getSystemService(DisplayManager::class.java).getDisplay(data.getInt(KEY_DISPLAY))
            ?: return failed("No such display.")
        val width = data.getInt(KEY_WIDTH).coerceAtLeast(1)
        val height = data.getInt(KEY_HEIGHT).coerceAtLeast(1)
        // A pane recreated (Split to Preview and back): the same engine, still running, for its new surface.
        engines[id]?.takeIf { it.uri != null }?.let { running ->
            try {
                running.rehost(token, display, width, height)
            } catch (e: Exception) {
                Log.w(TAG, "could not move a preview to its new surface", e)
                engines.remove(id)?.close()
                return failed("The preview could not move to the new pane: ${e.message}")
            }
            reply(replyTo, OPENED, id, Bundle().apply {
                putParcelable(KEY_SURFACE, running.host.surfacePackage)
                putString(KEY_URI, running.uri)
                putString(KEY_ISOLATE, running.isolate)
                putBoolean(KEY_REUSED, true)
            })
            return
        }
        engines.remove(id)?.close()
        val embedded = try {
            Embedded(token, display, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "could not start a preview engine", e)
            return failed("The Flutter engine did not start: ${e.message}")
        }
        engines[id] = embedded
        // The VM service and the isolate arrive a moment after the engine starts; wait for them off the main thread.
        Thread({
            val deadline = System.currentTimeMillis() + 20_000
            var uri: String? = null
            var isolate: String? = null
            var isolateDeadline = Long.MAX_VALUE
            while ((uri == null || isolate == null) && System.currentTimeMillis() < minOf(deadline, isolateDeadline)) {
                uri = uri ?: runCatching { FlutterJNI.getVMServiceUri() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?.also { isolateDeadline = System.currentTimeMillis() + FlutterPlayerActivity.ISOLATE_GRACE_MS }
                isolate = isolate ?: embedded.isolate
                if (uri == null || isolate == null) Thread.sleep(30)
            }
            main.post {
                if (engines[id] !== embedded) return@post
                if (uri == null) { failed("The Flutter engine started without a VM service."); return@post }
                embedded.uri = uri
                reply(replyTo, OPENED, id, Bundle().apply {
                    putParcelable(KEY_SURFACE, embedded.host.surfacePackage)
                    putString(KEY_URI, uri)
                    putString(KEY_ISOLATE, isolate)
                })
            }
        }, "flutter-preview-start").apply { isDaemon = true }.start()
    }

    private fun closeAll() {
        engines.values.forEach { it.close() }
        engines.clear()
    }

    private fun reply(to: Messenger?, what: Int, id: Int, data: Bundle) {
        to ?: return
        try {
            to.send(Message.obtain(null, what, id, 0).apply { this.data = data })
        } catch (_: RemoteException) {
            // The pane is gone; its unbinding closes the engine.
        }
    }

    private fun trusted(uid: Int): Boolean {
        if (uid == Process.myUid()) return true
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        if (packages.any { it in FlutterPlayerLauncher.TRUSTED_HOSTS }) return true
        return uid == SHELL_UID && (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** One pane's engine and the view it draws into the IDE's window. */
    @RequiresApi(30)
    private inner class Embedded(private var token: IBinder, private var display: android.view.Display, width: Int, height: Int) {
        // No plugin registrant: an app's Flutter plugins (packages with Android code) are not in the player.
        val engine = FlutterEngine(this@FlutterPreviewService, null, false)
        private val view = FlutterView(this@FlutterPreviewService, FlutterTextureView(this@FlutterPreviewService))
        var host = SurfaceControlViewHost(this@FlutterPreviewService, display, token)
            private set

        /** The engine's VM service, once it has one. */
        var uri: String? = null

        val isolate: String? get() = engine.dartExecutor.isolateServiceId

        /**
         * Show the same running view on a new pane surface. In the same window (the usual case: the pane was
         * recreated) the view host stays and only its size changes; each surface gets its own package of it.
         * In another window the view moves to a new host there.
         */
        fun rehost(token: IBinder, display: android.view.Display, width: Int, height: Int) {
            if (token == this.token && display.displayId == this.display.displayId) {
                host.relayout(width, height)
                return
            }
            host.release()
            host = SurfaceControlViewHost(this@FlutterPreviewService, display, token)
            host.setView(view, width, height)
            this.token = token
            this.display = display
        }

        init {
            engine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
            view.attachToFlutterEngine(engine)
            host.setView(view, width, height)
            engine.lifecycleChannel.appIsResumed()
        }

        /**
         * A touch or mouse event the pane received. The pane's surface is below the IDE's window, as a
         * `SurfaceView` is unless it is put on top (which would hide the IDE's own chrome over the pane), so the
         * IDE's window gets the input and the pane forwards it here, in the view's own coordinates.
         */
        fun input(event: MotionEvent) {
            if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked in POINTER_ONLY) view.dispatchGenericMotionEvent(event)
            else view.dispatchTouchEvent(event)
        }

        fun resize(width: Int, height: Int) {
            if (width > 0 && height > 0) host.relayout(width, height)
        }

        fun close() {
            runCatching { engine.lifecycleChannel.appIsDetached() }
            runCatching { view.detachFromFlutterEngine() }
            runCatching { host.release() }
            runCatching { engine.destroy() }
        }
    }

    companion object {
        private const val TAG = "FlutterPreviewService"
        /** adb's uid (android.os.Process.SHELL_UID, which the SDK does not expose). */
        private const val SHELL_UID = 2000
        const val CLASS = "dev.codeassist.dart.FlutterPreviewService"

        const val OPEN = 1
        const val RESIZE = 2
        const val CLOSE = 3
        const val INPUT = 4
        const val OPENED = 10
        const val FAILED = 11

        const val KEY_HOST_TOKEN = "hostToken"
        const val KEY_DISPLAY = "display"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_SURFACE = "surface"
        const val KEY_URI = "uri"
        const val KEY_ISOLATE = "isolate"
        const val KEY_REASON = "reason"
        const val KEY_EVENT = "event"
        const val KEY_REUSED = "reused"

        /** Pointer events that are not touches: hover and the mouse wheel. */
        private val POINTER_ONLY = setOf(
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_SCROLL,
        )
    }
}
