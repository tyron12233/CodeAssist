package dev.ide.android.preview

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Process
import dev.ide.core.LoweredComposePreview
import dev.ide.core.preview.ComposePreviewWireCodec
import dev.ide.platform.log.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * IDE-process client for [ComposePreviewSessionService]. Binds the `:preview` daemon and forwards a blocking
 * single-frame [renderOnce] to it (`docs/compose-preview-isolation.md`, Phase 1b): it serializes the lowered
 * preview with [ComposePreviewWireCodec] into the shared app cache, hands `:preview` the blob + classpath + res
 * roots, and maps the returned raw ARGB pixels back into a [Bitmap]. Links an [IBinder.DeathRecipient] so a
 * crash/OOM in `:preview` nulls the daemon (the caller falls back to the in-process host) instead of taking down
 * the IDE. `BIND_AUTO_CREATE` restarts the service for the next render.
 */
class ComposePreviewRemoteClient(context: Context) {
    private val appContext = context.applicationContext
    private val log = Log.logger("ide.preview.compose")
    private val lock = Object()
    private val seq = AtomicLong(0)

    @Volatile private var daemon: IComposePreviewSession? = null
    @Volatile private var bindRequested = false

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

    /** Eagerly start + bind `:preview` (forks the process) so the first render doesn't pay the bind latency. */
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
     * Render one frame of [lowered] in `:preview`. Returns the frame (+ the remote pid), or null if `:preview`
     * couldn't be reached or reported an error (→ the caller renders in-process). [classpath] carries the module
     * jars for library composables the bundled Compose lacks (empty → bundled-only).
     */
    fun renderOnce(
        lowered: LoweredComposePreview,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        night: Boolean,
        classpath: Array<String> = emptyArray(),
        resRoots: Array<String> = emptyArray(),
        packageName: String = "",
        minApi: Int = 26,
    ): RemoteFrame? {
        val d = awaitDaemon(BIND_TIMEOUT_MS) ?: return null
        val n = seq.incrementAndGet()
        val dir = File(appContext.cacheDir, "compose-preview").apply { mkdirs() }
        val blob = File(dir, "req-$n.blob")
        val out = File(dir, "frame-$n.px")
        return try {
            blob.writeBytes(ComposePreviewWireCodec.encode(lowered))
            val result = runCatching {
                d.renderOnce(blob.path, classpath, resRoots, packageName, minApi, widthPx, heightPx, density, night, out.path)
            }.getOrElse { log.warn("compose :preview renderOnce threw", it); return null }
            if (result == null || !result.startsWith("ok\t")) {
                log.warn("compose :preview render failed: $result")
                return null
            }
            val parts = result.removePrefix("ok\t").split("\t")
            val w = parts[0].toInt()
            val h = parts[1].toInt()
            val ints = IntArray(w * h)
            ByteBuffer.wrap(out.readBytes()).asIntBuffer().get(ints)
            RemoteFrame(Bitmap.createBitmap(ints, w, h, Bitmap.Config.ARGB_8888), runCatching { d.pid() }.getOrDefault(-1))
        } finally {
            blob.delete()
            out.delete()
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L
    }
}
