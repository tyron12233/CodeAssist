package dev.ide.android.preview

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ide.android.DexPeerFactory
import dev.ide.core.LoweredComposePreview
import dev.ide.core.preview.ComposePreviewWireCodec
import dev.ide.interp.compose.ComposePreviewRenderer
import dev.ide.interp.compose.VmLibraryExecutor
import dev.ide.platform.log.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The `:preview` OS process for Compose `@Preview` rendering (`docs/compose-preview-isolation.md`, Phase 2) —
 * the Compose counterpart to [PreviewRenderService] (XML/real-view). It hosts persistent [Session]s: each decodes
 * the lowered preview the IDE serialized with [ComposePreviewWireCodec], interprets it via [ComposePreviewRenderer],
 * and renders it off the IDE's own composition into an [OffscreenComposeSurface] (VirtualDisplay + Presentation +
 * ComposeView) — the material3-flip render (bridged composer against this APK's bundled Compose). Frames STREAM
 * back over [IComposePreviewCallback] (pixels on the shared FS); [IComposePreviewSession.update] pushes a
 * re-lowered program for live edit (remembered state in the slot table survives). Running it here means a runaway
 * recomposition or crash pegs/kills only `:preview`; the IDE's [ComposePreviewRemoteClient] links a
 * `DeathRecipient` and falls back to the in-process host.
 */
class ComposePreviewSessionService : Service() {

    private val log = Log.logger("ide.preview.compose")
    private val sessions = ConcurrentHashMap<Int, Session>()
    private val nextId = AtomicInteger(1)

    private val binder = object : IComposePreviewSession.Stub() {
        override fun pid(): Int = Process.myPid()

        override fun open(
            blobFile: String?,
            classpath: Array<out String>?,
            resRoots: Array<out String>?,
            packageName: String?,
            minApi: Int,
            widthPx: Int,
            heightPx: Int,
            density: Float,
            night: Boolean,
            frameDir: String?,
            cb: IComposePreviewCallback?,
        ): Int = runCatching {
            val lowered = ComposePreviewWireCodec.decode(File(blobFile!!).readBytes())
            val id = nextId.getAndIncrement()
            val session = Session(id, widthPx, heightPx, density, File(frameDir!!).apply { mkdirs() }, cb!!, buildExecutor(classpath))
            session.start(lowered)
            sessions[id] = session
            log.info(":preview(pid=${Process.myPid()}): opened compose session $id (${widthPx}x$heightPx)")
            id
        }.getOrElse {
            runCatching { cb?.onError("open failed: ${it.javaClass.simpleName}: ${it.message}") }
            log.warn("compose session open failed", it)
            -1
        }

        override fun update(sessionId: Int, blobFile: String?) {
            val session = sessions[sessionId] ?: return
            runCatching { session.update(ComposePreviewWireCodec.decode(File(blobFile!!).readBytes())) }
                .onFailure { log.warn("compose session $sessionId update failed", it) }
        }

        override fun resize(sessionId: Int, widthPx: Int, heightPx: Int, density: Float, night: Boolean) {
            sessions[sessionId]?.resize(widthPx, heightPx, density)
        }

        override fun close(sessionId: Int) {
            sessions.remove(sessionId)?.let { runCatching { it.close() } }
        }
    }

    /** The bytecode VM executor for library composables the bundled Compose lacks; null when the classpath is
     *  empty (bundled-only, the common case). */
    private fun buildExecutor(classpath: Array<out String>?): VmLibraryExecutor? =
        classpath?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { cp ->
            VmLibraryExecutor(
                cp.map { Paths.get(it) },
                peerFactory = DexPeerFactory(File(cacheDir, "vm-peer-dex").toPath(), proxyExceptionSink = { t ->
                    log.warn("interpreted preview peer call failed (skipped): ${t.message ?: t.javaClass.simpleName}")
                }),
            )
        }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        super.onDestroy()
    }

    /**
     * A live off-screen composition of one preview. Its program is a Compose state so [update] (a live edit)
     * writes a new lowered program on the main thread → recomposition → a fresh frame. Frames are written to
     * [frameDir] and announced over [cb]; the renderer is remembered across recompositions so its live-edit
     * identity-diff preserves remembered state.
     */
    private inner class Session(
        val id: Int,
        @Volatile var width: Int,
        @Volatile var height: Int,
        @Volatile var density: Float,
        val frameDir: File,
        val cb: IComposePreviewCallback,
        val executor: VmLibraryExecutor?,
    ) {
        private var surface = newSurface()
        private val programState = mutableStateOf<LoweredComposePreview?>(null)
        private val seq = AtomicLong(0)
        @Volatile private var lastError: String? = null

        private fun newSurface() = OffscreenComposeSurface(applicationContext, width, height, (density * 160f).toInt().coerceAtLeast(1))

        fun start(lowered: LoweredComposePreview) {
            programState.value = lowered
            surface.onFrame = { frame -> pushFrame(frame) }
            surface.start {
                val program by programState
                val p = program
                if (p != null) {
                    val renderer = remember { ComposePreviewRenderer(libraryExecutor = executor) }
                    val onErr: @Composable (Throwable) -> Unit = { t -> reportError(t) }
                    renderer.Render(p.entry, p.program, p.classes, emptyList(), onErr) {}
                }
            }
        }

        fun update(lowered: LoweredComposePreview) {
            surface.runOnMain { programState.value = lowered }
        }

        fun resize(newWidth: Int, newHeight: Int, newDensity: Float) {
            val current = programState.value ?: return
            runCatching { surface.close() }
            width = newWidth; height = newHeight; density = newDensity
            surface = newSurface()
            start(current)
        }

        fun close() {
            runCatching { surface.close() }
            runCatching { executor?.close() }
            runCatching { frameDir.listFiles()?.forEach { it.delete() } }
        }

        private fun pushFrame(frame: OffscreenComposeSurface.Frame) {
            val s = seq.incrementAndGet()
            val f = File(frameDir, "frame-$s.px")
            runCatching {
                val bb = ByteBuffer.allocate(frame.pixels.size * 4)
                bb.asIntBuffer().put(frame.pixels)
                f.outputStream().use { it.write(bb.array()) }
                cb.onFrame(f.path, frame.width, frame.height, s)
            }.onFailure { log.warn("compose session $id frame push failed", it) }
        }

        private fun reportError(t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message ?: ""}".trim()
            if (msg != lastError) {
                lastError = msg
                runCatching { cb.onError(msg) }
            }
        }
    }
}
