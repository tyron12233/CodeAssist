package dev.ide.preview.realview

import android.content.Context
import android.graphics.Bitmap
import dev.ide.android.AndroidIde
import dev.ide.android.preview.PreviewRenderClient
import dev.ide.preview.impl.PreviewViewTreeCodec
import dev.ide.preview.impl.RealViewRequest
import dev.ide.preview.impl.RealViewResult
import dev.ide.preview.impl.RealViewRuntime
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.graphics.createBitmap

/**
 * A [RealViewRuntime] that runs the inflate + draw in the separate `:preview` process (via
 * [PreviewRenderClient]) so a crash/OOM while rendering arbitrary library/user View code kills only that
 * process, not the IDE — Step 3 of the layoutlib-on-device work. It holds an in-process [AndroidRealViewRuntime]
 * as a fallback: used when the "separate process" setting is off, or when `:preview` can't be reached (it died
 * and hasn't restarted yet). A genuine render failure reported by the daemon ("err\t…") is returned as-is (no
 * in-process retry — it would fail the same way and double the work).
 *
 * The daemon returns the bitmap as raw ARGB_8888 pixels in a file under the shared app cache (no large payload
 * over Binder, no PNG); this maps them back into a [Bitmap] returned as [RealViewResult.nativeBitmap].
 */
class RemoteRealViewRuntime(
    context: Context,
    androidJar: Path,
    cacheDir: File,
    deviceApiLevel: Int,
    private val separateProcessEnabled: () -> Boolean,
) : RealViewRuntime {

    private val client = PreviewRenderClient(context)

    // In-process fallback shares the build's dex cache too (same location the :preview daemon uses).
    private val dexCacheRoot =
        File(AndroidIde.appHomeDir(context), "caches/dex").toPath()
    private val local = AndroidRealViewRuntime(
        context,
        androidJar,
        File(cacheDir, "local"),
        deviceApiLevel,
        dexCacheRoot
    )
    private val handoffDir = File(cacheDir, "io")
    private val counter = AtomicInteger(0)

    // Fine stages streamed from :preview are relayed to the current render's listener (→ the status chip).
    @Volatile
    private var currentStageListener: ((String) -> Unit)? = null

    init {
        client.onStage = { stage -> currentStageListener?.invoke(stage) }
        // Eagerly fork + bind :preview at project open, so the first render doesn't pay the bind latency.
        client.warmUp()
    }

    override fun render(request: RealViewRequest): RealViewResult {
        if (!separateProcessEnabled()) return local.render(request)
        val outFile = File(
            handoffDir,
            "render-${counter.incrementAndGet()}.argb"
        ).apply { parentFile?.mkdirs() }
        currentStageListener = request.stageListener
        val res = try {
            client.render(
                request.layoutName,
                request.widthPx,
                request.heightPx,
                request.density,
                request.night,
                request.resourcesAp.toString(),
                request.classpath.map { it.toString() }.toTypedArray(),
                request.packageName,
                request.themeName,
                request.minApi,
                request.interpretClasses,
                outFile.absolutePath,
            )
        } finally {
            currentStageListener = null
        } ?: run {
            outFile.delete()
            // :preview unreachable (not bound / died) → render in-process
            return local.render(request)
        }
        return parseResult(res, outFile)
    }

    private fun parseResult(res: String, outFile: File): RealViewResult {
        val treeFile = File(outFile.absolutePath + ".tree")
        val parts = res.split('\t')
        try {
            if (parts.firstOrNull() != "ok") {
                return RealViewResult(
                    null,
                    error = parts.drop(1).joinToString("\t").ifBlank { "preview render failed" })
            }
            val w = parts.getOrNull(1)?.toIntOrNull()
            val h = parts.getOrNull(2)?.toIntOrNull()
            if (w == null || h == null || w <= 0 || h <= 0) return RealViewResult(
                null,
                error = "bad render result: $res"
            )
            val bytes = outFile.readBytes()
            if (bytes.size < w * h * 4) return RealViewResult(
                null,
                error = "truncated render pixels"
            )
            val bmp = createBitmap(w, h)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            // The captured hierarchy, written by the daemon as a sidecar next to the pixels (best-effort — a
            // missing/garbled tree just omits the inspector; the render still shows).
            val tree = runCatching {
                if (treeFile.exists()) PreviewViewTreeCodec.decode(treeFile.readText()) else null
            }.getOrNull()
            return RealViewResult(
                pngBytes = null,
                width = w,
                height = h,
                nativeBitmap = bmp,
                viewTree = tree
            )
        } catch (t: Throwable) {
            return RealViewResult(
                null,
                error = "read preview pixels: ${t.message ?: t.javaClass.simpleName}"
            )
        } finally {
            runCatching { outFile.delete() }
            runCatching { treeFile.delete() }
        }
    }
}
