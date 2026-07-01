package dev.ide.android.preview

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import dev.ide.platform.log.Log

/**
 * UI-process client for [PreviewRenderService]. Binds the `:preview` daemon and forwards a blocking
 * [render] call to it; links an [IBinder.DeathRecipient] so a crash/OOM in `:preview` is observed (the daemon
 * reference is nulled and the caller falls back to in-process rendering) instead of taking down the IDE.
 * `BIND_AUTO_CREATE` restarts the service for the next render. The first [render] waits briefly for the bind.
 */
class PreviewRenderClient(context: Context) {
    private val appContext = context.applicationContext
    private val log = Log.logger("ide.preview")
    private val lock = Object()

    @Volatile private var daemon: IPreviewRenderer? = null
    @Volatile private var bindRequested = false

    /** Fine render-stage updates from the daemon ("Dexing"/"Inflating"/"Drawing"), on a Binder thread. */
    @Volatile var onStage: ((String) -> Unit)? = null

    private val stageCallback = object : IPreviewStageCallback.Stub() {
        override fun onStage(stage: String?) { stage?.let { onStage?.invoke(it) } }
    }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            log.warn("ui(pid=${Process.myPid()}): :preview died (binderDied) — IDE SURVIVED, falling back in-process.")
            synchronized(lock) { daemon = null }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val d = IPreviewRenderer.Stub.asInterface(service)
            runCatching { service?.linkToDeath(deathRecipient, 0) }
            runCatching { d.registerStageCallback(stageCallback) }
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
            appContext.bindService(Intent(appContext, PreviewRenderService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { bindRequested = false }
    }

    /** Eagerly start + bind `:preview` (forks the process) so the first render doesn't pay the bind latency. */
    fun warmUp() = ensureBound()

    /** The live daemon, waiting up to [timeoutMs] for the (re)bind; null if it didn't connect in time. */
    private fun awaitDaemon(timeoutMs: Long): IPreviewRenderer? {
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

    /** Render in `:preview`; returns the daemon's result string ("ok\t<w>\t<h>" / "err\t…"), or null if the
     *  daemon couldn't be reached (→ the caller renders in-process). */
    fun render(
        layoutName: String, widthPx: Int, heightPx: Int, density: Float, night: Boolean,
        resourcesAp: String, classpath: Array<String>, packageName: String, themeName: String?, minApi: Int, outFile: String,
    ): String? {
        val d = awaitDaemon(BIND_TIMEOUT_MS) ?: return null
        return runCatching {
            d.render(layoutName, widthPx, heightPx, density, night, resourcesAp, classpath, packageName, themeName, minApi, outFile)
        }.getOrNull()
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L
    }
}
