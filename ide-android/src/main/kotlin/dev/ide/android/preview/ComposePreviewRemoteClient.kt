package dev.ide.android.preview

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import android.os.Process
import dev.ide.core.LoweredComposePreview
import dev.ide.core.preview.ComposePreviewWireCodec
import dev.ide.platform.log.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * IDE-process client for [ComposePreviewSessionService]. Binds the `:preview` daemon and opens streaming preview
 * [Session]s (`docs/compose-preview-isolation.md`, Phase 2): it serializes the lowered preview with
 * [ComposePreviewWireCodec] into the shared app cache, hands `:preview` the blob + classpath + res roots, and
 * receives frames over an [IComposePreviewCallback] — each frame's raw ARGB pixels read from the session frame
 * dir and mapped into a [Bitmap] for the caller's [FrameSink]. [Session.update] pushes a re-lowered program (live
 * edit). Links an [IBinder.DeathRecipient] so a crash/OOM in `:preview` nulls the daemon (the caller falls back
 * to the in-process host) instead of taking down the IDE. `BIND_AUTO_CREATE` restarts the service for the next open.
 */
class ComposePreviewRemoteClient(context: Context) {
    private val appContext = context.applicationContext
    private val log = Log.logger("ide.preview.compose")
    private val lock = Object()
    private val seq = AtomicLong(0)

    @Volatile private var daemon: IComposePreviewSession? = null
    @Volatile private var bindRequested = false

    /** Receives decoded frames (and errors) for a [Session], on a Binder thread — post to the UI thread. */
    interface FrameSink {
        fun onFrame(bitmap: Bitmap, seq: Long)
        fun onError(message: String)
    }

    /** A rendered frame + the `:preview` process id it was rendered in (so callers can confirm isolation). */
    class RemoteFrame(val bitmap: Bitmap, val remotePid: Int)

    private val deathRecipient = IBinder.DeathRecipient {
        log.warn("ui(pid=${Process.myPid()}): :preview died (binderDied). IDE SURVIVED, falling back in-process.")
        synchronized(lock) { daemon = null }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val d = IComposePreviewSession.Stub.asInterface(service)
            runCatching { service?.linkToDeath(deathRecipient, 0) }
            synchronized(lock) { daemon = d; lock.notifyAll() }
            log.info("ui(pid=${Process.myPid()}): connected to :preview(pid=${runCatching { d.pid() }.getOrDefault(-1)})")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { daemon = null }
        }
    }

    private fun ensureBound() {
        if (bindRequested) return
        bindRequested = true
        runCatching {
            appContext.bindService(Intent(appContext, ComposePreviewSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { bindRequested = false }
    }

    /** Eagerly start + bind `:preview` (forks the process) so the first open doesn't pay the bind latency. */
    fun warmUp() = ensureBound()

    private fun awaitDaemon(timeoutMs: Long): IComposePreviewSession? {
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

    /**
     * Open a streaming session rendering [lowered] in `:preview`; frames arrive on [sink]. Returns the handle, or
     * null if `:preview` couldn't be reached or the open failed (→ the caller renders in-process). [classpath]
     * carries the module jars for library composables the bundled Compose lacks (empty → bundled-only).
     */
    fun openSession(
        lowered: LoweredComposePreview,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        night: Boolean,
        sink: FrameSink,
        classpath: Array<String> = emptyArray(),
        resRoots: Array<String> = emptyArray(),
        packageName: String = "",
        minApi: Int = 26,
    ): Session? {
        val d = awaitDaemon(BIND_TIMEOUT_MS) ?: return null
        val local = seq.incrementAndGet()
        val dir = File(appContext.cacheDir, "compose-preview/session-$local").apply { mkdirs() }
        val callback = object : IComposePreviewCallback.Stub() {
            override fun onFrame(frameFile: String?, widthPx: Int, heightPx: Int, s: Long) {
                val bmp = runCatching { readFrame(File(frameFile!!), widthPx, heightPx) }.getOrNull() ?: return
                runCatching { File(frameFile!!).delete() }
                sink.onFrame(bmp, s)
            }
            override fun onFrameBuffer(buffer: HardwareBuffer?, widthPx: Int, heightPx: Int, s: Long) {
                if (buffer == null) return
                // Wrap the shared GPU buffer directly — no pixel copy. Our HardwareBuffer handle can be closed once
                // the (hardware) bitmap holds its reference. Only reached on API 29+ (:preview gates the fast path).
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)) }.getOrNull()
                } else null
                runCatching { buffer.close() }
                if (bmp != null) sink.onFrame(bmp, s)
            }
            override fun onError(message: String?) { sink.onError(message ?: "unknown error") }
        }
        val blob = File(dir, "req-open.blob")
        return runCatching {
            blob.writeBytes(ComposePreviewWireCodec.encode(lowered))
            val id = d.open(blob.path, classpath, resRoots, packageName, minApi, widthPx, heightPx, density, night, dir.path, callback)
            if (id < 0) { dir.deleteRecursively(); null }
            else Session(d, id, runCatching { d.pid() }.getOrDefault(-1), dir)
        }.getOrElse { log.warn("compose openSession threw", it); dir.deleteRecursively(); null }
    }

    /** Convenience: open a session, block up to [timeoutMs] for its first frame, close, and return it (+ the
     *  remote pid). Used by the isolation spikes; the live UI opens a persistent [Session] instead. */
    fun renderOnce(
        lowered: LoweredComposePreview,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        night: Boolean,
        classpath: Array<String> = emptyArray(),
        resRoots: Array<String> = emptyArray(),
        namespace: String = "",
        timeoutMs: Long = 8_000,
    ): RemoteFrame? {
        val latch = CountDownLatch(1)
        val first = AtomicReference<Bitmap?>(null)
        val sink = object : FrameSink {
            override fun onFrame(bitmap: Bitmap, seq: Long) { if (first.compareAndSet(null, bitmap)) latch.countDown() }
            override fun onError(message: String) { latch.countDown() }
        }
        val session = openSession(lowered, widthPx, heightPx, density, night, sink, classpath, resRoots, namespace) ?: return null
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            first.get()?.let { RemoteFrame(it, session.remotePid) }
        } finally {
            session.close()
        }
    }

    private fun readFrame(file: File, w: Int, h: Int): Bitmap {
        // The file holds raw RGBA_8888 bytes — map them straight into the bitmap. ARGB_8888's in-memory layout IS
        // RGBA, so copyPixelsFromBuffer is a single native copy (no per-pixel int decode like the old asIntBuffer).
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(file.readBytes()))
        return bmp
    }

    /** A live remote preview session: push edits via [update], re-target via [resize], tear down via [close]. */
    inner class Session(
        private val daemon: IComposePreviewSession,
        val id: Int,
        val remotePid: Int,
        private val frameDir: File,
    ) {
        fun update(lowered: LoweredComposePreview) {
            runCatching {
                val encoded = ComposePreviewWireCodec.encode(lowered)
                // Carry the blob inline over Binder when it fits under the async transaction buffer (the common
                // case — a lowered preview is a few KB), saving a file write here + a re-read in `:preview` on every
                // keystroke. A larger program falls back to the shared file so it can't blow the transaction limit.
                if (encoded.size <= INLINE_UPDATE_MAX_BYTES) {
                    daemon.updateBytes(id, encoded)
                } else {
                    val blob = File(frameDir, "req-update.blob")
                    blob.writeBytes(encoded)
                    daemon.update(id, blob.path)
                }
            }.onFailure { log.warn("compose session $id update failed", it) }
        }

        fun resize(widthPx: Int, heightPx: Int, density: Float, night: Boolean) {
            runCatching { daemon.resize(id, widthPx, heightPx, density, night) }
        }

        /** Forward a pointer event ([action] a MotionEvent action; [x]/[y] in the off-screen canvas' pixel space)
         *  into the remote composition. oneway — returns immediately, so a MOVE stream never blocks the UI. */
        fun dispatchInput(action: Int, x: Float, y: Float, pointerId: Int, eventTimeMs: Long) {
            runCatching { daemon.dispatchInput(id, action, x, y, pointerId, eventTimeMs) }
        }

        /** Forward a key event ([action] a KeyEvent action; [keyCode] a KeyEvent.KEYCODE_*; [metaState] modifiers)
         *  into the remote composition. oneway. Hardware-keyboard / nav keys only — soft-keyboard text is the IME
         *  bridge. */
        fun dispatchKey(action: Int, keyCode: Int, metaState: Int, eventTimeMs: Long) {
            runCatching { daemon.dispatchKey(id, action, keyCode, metaState, eventTimeMs) }
        }

        fun close() {
            runCatching { daemon.close(id) }
            runCatching { frameDir.deleteRecursively() }
        }
    }

    companion object {
        private const val BIND_TIMEOUT_MS = 10_000L

        /** Live-edit blobs at or below this go inline over the oneway Binder call; larger ones use the shared file.
         *  256 KB sits well under the ~1 MB Binder transaction limit (and the async buffer half of it). */
        private const val INLINE_UPDATE_MAX_BYTES = 256 * 1024

        @Volatile private var instance: ComposePreviewRemoteClient? = null

        /** The process-wide client (one Binder connection to `:preview` shared by every open preview). */
        fun get(context: Context): ComposePreviewRemoteClient =
            instance ?: synchronized(this) {
                instance ?: ComposePreviewRemoteClient(context.applicationContext).also { instance = it }
            }
    }
}
