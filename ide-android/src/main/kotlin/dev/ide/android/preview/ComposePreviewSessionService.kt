package dev.ide.android.preview

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ide.android.DexPeerFactory
import dev.ide.core.preview.ComposePreviewWireCodec
import dev.ide.interp.compose.ComposePreviewRenderer
import dev.ide.interp.compose.VmLibraryExecutor
import dev.ide.platform.log.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

/**
 * The `:preview` OS process for Compose `@Preview` rendering (`docs/compose-preview-isolation.md`, Phase 1b) —
 * the Compose counterpart to [PreviewRenderService] (XML/real-view). It decodes the lowered preview the IDE
 * serialized with [ComposePreviewWireCodec], interprets it via [ComposePreviewRenderer], and renders it off the
 * IDE's own composition into an [OffscreenComposeSurface] (VirtualDisplay + Presentation + ComposeView) — the
 * material3-flip render (bridged composer against this APK's bundled Compose). Running it here means a runaway
 * recomposition or a crash pegs/kills only `:preview`; the IDE's [ComposePreviewRemoteClient] links a
 * `DeathRecipient` and falls back to the in-process host.
 *
 * Phase 1b renders a single frame per [renderOnce] call and hands the raw ARGB_8888 pixels back on the shared
 * filesystem (the `:build`/XML "control over Binder, bulk over the FS" convention). Continuous frames
 * (HardwareBuffer), input forwarding, and live-edit `update` arrive in Phases 2-4.
 */
class ComposePreviewSessionService : Service() {

    private val log = Log.logger("ide.preview.compose")

    private val binder = object : IComposePreviewSession.Stub() {
        override fun pid(): Int = Process.myPid()

        override fun renderOnce(
            blobFile: String?,
            classpath: Array<out String>?,
            resRoots: Array<out String>?,
            packageName: String?,
            minApi: Int,
            widthPx: Int,
            heightPx: Int,
            density: Float,
            night: Boolean,
            outFile: String?,
        ): String = runCatching {
            val lowered = ComposePreviewWireCodec.decode(File(blobFile!!).readBytes())
            val densityDpi = (density * 160f).toInt().coerceAtLeast(1)

            // Library composables the bundled Compose lacks run interpreted from the project jars (empty
            // classpath → bundled-only, the common case for a standard preview). The peer-dex cache is
            // process-local; the IDE's editor host shares a workspace-wide one, but :preview is short-lived here.
            val libraryExecutor = classpath?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { cp ->
                VmLibraryExecutor(
                    cp.map { Paths.get(it) },
                    peerFactory = DexPeerFactory(File(cacheDir, "vm-peer-dex").toPath(), proxyExceptionSink = { t ->
                        log.warn("interpreted preview peer call failed (skipped): ${t.message ?: t.javaClass.simpleName}")
                    }),
                )
            }

            val surface = OffscreenComposeSurface(applicationContext, widthPx, heightPx, densityDpi)
            try {
                val error = AtomicReference<Throwable?>(null)
                val frame = surface.renderFirstFrame(RENDER_TIMEOUT_MS) {
                    val renderer = remember { ComposePreviewRenderer(libraryExecutor = libraryExecutor) }
                    val onErr: @Composable (Throwable) -> Unit = { t -> error.set(t) }
                    renderer.Render(lowered.entry, lowered.program, lowered.classes, emptyList(), onErr) {}
                } ?: return@runCatching "err\tno frame produced within ${RENDER_TIMEOUT_MS}ms"

                error.get()?.let { return@runCatching "err\t${it.javaClass.simpleName}: ${it.message ?: ""}".trim() }
                writePixels(frame, File(outFile!!))
                "ok\t${frame.width}\t${frame.height}"
            } finally {
                surface.close()
                libraryExecutor?.close()
            }
        }.getOrElse { "err\t${it.javaClass.simpleName}: ${it.message ?: ""}".trim() }
    }

    /** Write the frame's ARGB_8888 pixels (width*height*4 bytes, big-endian ints) to [out] for the IDE to map
     *  back with `Bitmap.createBitmap(ints, w, h, ARGB_8888)` (see [ComposePreviewRemoteClient]). */
    private fun writePixels(frame: OffscreenComposeSurface.Frame, out: File) {
        val bb = ByteBuffer.allocate(frame.pixels.size * 4)
        bb.asIntBuffer().put(frame.pixels)
        out.parentFile?.mkdirs()
        out.outputStream().use { it.write(bb.array()) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val RENDER_TIMEOUT_MS = 8_000L
    }
}
