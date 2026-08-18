package dev.ide.android.preview

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Process
import dev.ide.platform.log.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IDE-process client for [SwingRunSessionService]: binds the `:preview` daemon, starts a Swing program there,
 * and turns the frames it streams back into [Bitmap]s for the Run pane.
 *
 * The sibling of [ComposePreviewRemoteClient], and deliberately shaped like it, with one difference that
 * matters: a preview can fall back to rendering in-process when `:preview` dies, but a RUN cannot. The program
 * and everything it had in memory died with the process, so [Session.onExited] fires and the run is over.
 */
class SwingRunRemoteClient(context: Context) {

    private val appContext = context.applicationContext
    private val log = Log.logger("ide.preview.swing")
    private val lock = Object()

    @Volatile private var daemon: ISwingRunSession? = null
    @Volatile private var bindRequested = false

    /** Where a running program's output goes. Every callback arrives on a Binder thread: post to the UI thread. */
    interface Host {
        /** A new frame of the program's window. */
        fun onFrame(bitmap: Bitmap, seq: Long)

        /** The program's stdout/stderr, as raw text (chunks may be partial lines). */
        fun onOutput(text: String)

        /** The program finished. [error] is empty on a clean exit. */
        fun onExited(exitCode: Int, error: String)
    }

    private val deathRecipient = IBinder.DeathRecipient {
        log.warn("ide(pid=${Process.myPid()}): :preview died (binderDied). The IDE survived; running programs are gone.")
        val dead = synchronized(lock) {
            daemon = null
            sessions.toList().also { sessions.clear() }
        }
        dead.forEach { it.processDied() }
    }

    private val sessions = ArrayList<Session>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val d = ISwingRunSession.Stub.asInterface(service)
            runCatching { service?.linkToDeath(deathRecipient, 0) }
            synchronized(lock) {
                daemon = d
                lock.notifyAll()
            }
            log.info("ide(pid=${Process.myPid()}): connected to :preview(pid=${runCatching { d.pid() }.getOrDefault(-1)})")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { daemon = null }
        }
    }

    private fun ensureBound() {
        if (bindRequested) return
        bindRequested = true
        runCatching {
            appContext.bindService(
                Intent(appContext, SwingRunSessionService::class.java), connection, Context.BIND_AUTO_CREATE,
            )
        }.onFailure { bindRequested = false }
    }

    /** Bind `:preview` ahead of time (it forks a process), so the first Run does not pay the bind latency. */
    fun warmUp() = ensureBound()

    private fun awaitDaemon(timeoutMs: Long): ISwingRunSession? {
        ensureBound()
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (daemon == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                runCatching { lock.wait(remaining) }
            }
            return daemon
        }
    }

    /** The `:preview` process id, or -1 when it is not up. Callers use it to confirm the run is isolated. */
    fun remotePid(timeoutMs: Long = BIND_TIMEOUT_MS): Int =
        runCatching { awaitDaemon(timeoutMs)?.pid() ?: -1 }.getOrDefault(-1)

    /**
     * Start [mainClass] from [classpath] in `:preview`, painting its windows at [widthPx] x [heightPx]. Returns
     * null when the daemon could not be reached or refused the program; [host] then never fires.
     */
    fun start(
        classpath: List<String>,
        mainClass: String,
        args: List<String>,
        widthPx: Int,
        heightPx: Int,
        frameDir: File,
        host: Host,
        /**
         * When set, frames are handed over as the FILE they arrived in and [Host.onFrame] is not called.
         *
         * A caller that draws the frame itself wants a [Bitmap]; a caller that only forwards it onward (the run
         * engine, whose screen is in another process again) wants the path, because decoding pixels just to
         * hand them to someone else is pure waste. Whoever takes the path owns deleting it.
         */
        rawFrames: ((path: String, widthPx: Int, heightPx: Int, seq: Long) -> Unit)? = null,
    ): Session? {
        val remote = awaitDaemon(BIND_TIMEOUT_MS) ?: run {
            log.warn("could not reach :preview to run $mainClass")
            return null
        }
        frameDir.mkdirs()
        val session = Session(remote, host, rawFrames)
        val id = runCatching {
            remote.open(
                classpath.toTypedArray(), mainClass, args.toTypedArray(),
                widthPx, heightPx, frameDir.path, session.callback,
            )
        }.getOrElse {
            log.warn("failed to start $mainClass in :preview", it)
            -1
        }
        if (id < 0) return null
        session.bind(id)
        synchronized(lock) { sessions.add(session) }
        return session
    }

    /** One running program. Everything here is safe to call after it has exited; the calls become no-ops. */
    inner class Session internal constructor(
        private val remote: ISwingRunSession,
        private val host: Host,
        private val rawFrames: ((String, Int, Int, Long) -> Unit)? = null,
    ) {
        private var id = -1
        private val finished = AtomicBoolean(false)

        internal fun bind(sessionId: Int) {
            id = sessionId
        }

        /** Forward a pointer event. [action] is a `RunPointer` constant; [x]/[y] are in the frame's pixel
         *  space, which the pane maps from the touch. */
        fun pointer(action: Int, x: Float, y: Float) {
            if (finished.get()) return
            runCatching { remote.dispatchPointer(id, action, x, y, System.currentTimeMillis()) }
        }

        /** Forward a key event to whatever component holds focus in the program's window. */
        fun key(action: Int, keyCode: Int, keyChar: Char) {
            if (finished.get()) return
            runCatching { remote.dispatchKey(id, action, keyCode, keyChar.code, System.currentTimeMillis()) }
        }

        /** Re-target the painted surface when the Run pane resizes. */
        fun resize(widthPx: Int, heightPx: Int) {
            if (finished.get()) return
            runCatching { remote.resize(id, widthPx, heightPx) }
        }

        /** Stop the run: what the console's Stop button reaches. */
        fun stop() {
            if (finished.get()) return
            runCatching { remote.close(id) }
        }

        /** `:preview` died under us, so the program went with it. */
        internal fun processDied() = finish(EXIT_PROCESS_DIED, "the preview process stopped unexpectedly")

        private fun finish(code: Int, error: String) {
            if (finished.getAndSet(true)) return
            synchronized(lock) { sessions.remove(this) }
            runCatching { host.onExited(code, error) }
        }

        internal val callback = object : ISwingRunCallback.Stub() {

            override fun onFrame(frameFile: String?, widthPx: Int, heightPx: Int, seq: Long) {
                val path = frameFile ?: return
                rawFrames?.let { forward ->
                    runCatching { forward(path, widthPx, heightPx, seq) }
                    return
                }
                val file = File(path)
                val bytes = runCatching { file.readBytes() }.getOrNull()
                file.delete()
                if (bytes == null || widthPx <= 0 || heightPx <= 0) return
                val bitmap = runCatching {
                    Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                        .apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
                }.getOrNull() ?: return
                runCatching { host.onFrame(bitmap, seq) }
            }

            override fun onOutput(text: String?) {
                if (!text.isNullOrEmpty()) runCatching { host.onOutput(text) }
            }

            override fun onExited(exitCode: Int, error: String?) = finish(exitCode, error ?: "")

            override fun onError(message: String?) {
                finish(EXIT_FAILED, message ?: "the program could not be started")
            }
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L

        /** Exit code for a run whose process died, distinct from any the program itself can report. */
        const val EXIT_PROCESS_DIED = 137

        /** Exit code for a run that never started. */
        const val EXIT_FAILED = 1
    }
}
