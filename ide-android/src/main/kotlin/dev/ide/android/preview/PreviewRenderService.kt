package dev.ide.android.preview

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.Process
import dev.ide.android.AndroidIde
import dev.ide.preview.impl.PreviewViewTreeCodec
import dev.ide.preview.impl.RealViewRequest
import dev.ide.preview.realview.AndroidRealViewRuntime
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Paths

/**
 * The `:preview` OS process for real-view layout rendering (Step 3 of the layoutlib-on-device work; the
 * counterpart to the `:build` BuildDaemonService). It hosts an [AndroidRealViewRuntime] and renders a
 * self-contained request — the UI already relinked the live buffer into `resources.ap_`, so this process needs
 * only `android.jar` + the request, never the project model or build engine. Running the inflate + draw of
 * arbitrary library/user View code here means a crash or OOM kills only `:preview`; the UI's
 * [PreviewRenderClient] links a `DeathRecipient` and falls back to in-process rendering.
 *
 * The rendered bitmap is handed back as raw ARGB_8888 pixels written to a file in the shared app cache (the
 * `:build` daemon's "control over IPC, bulk on the shared filesystem" convention), so no large payload crosses
 * Binder and there is no PNG encode/decode.
 */
class PreviewRenderService : Service() {

    // android.jar is provisioned per-process (idempotent, marker-guarded); the runtime's own dex/oat cache
    // lives under this process's cache dir.
    private val runtime by lazy {
        val androidJar = AndroidIde.provisionAndroidJar(applicationContext).toPath()
        // The build's shared library-dex cache (`<appHome>/caches/dex`), so the preview dexes into — and reuses
        // from — the SAME cache the build uses.
        val dexCacheRoot = File(AndroidIde.appHomeDir(applicationContext), "caches/dex").toPath()
        AndroidRealViewRuntime(applicationContext, androidJar, File(cacheDir, "preview-render-rt"), Build.VERSION.SDK_INT, dexCacheRoot)
    }

    @Volatile private var stageCallback: IPreviewStageCallback? = null

    private val binder = object : IPreviewRenderer.Stub() {
        override fun pid(): Int = Process.myPid()

        override fun registerStageCallback(cb: IPreviewStageCallback?) {
            stageCallback = cb
        }

        override fun render(
            layoutName: String?, widthPx: Int, heightPx: Int, density: Float, night: Boolean,
            resourcesAp: String?, classpath: Array<out String>?, packageName: String?, themeName: String?, minApi: Int, outFile: String?,
        ): String {
            return runCatching {
                val req = RealViewRequest(
                    layoutName = layoutName.orEmpty(),
                    layoutText = "", // unused by the runtime; the live edit is already in resourcesAp
                    widthPx = widthPx, heightPx = heightPx, density = density, night = night,
                    resourcesAp = Paths.get(resourcesAp!!),
                    classpath = classpath?.map { Paths.get(it) } ?: emptyList(),
                    packageName = packageName.orEmpty(),
                    themeName = themeName,
                    minApi = minApi,
                ).apply {
                    // Stream fine stages ("Dexing"/"Inflating"/"Drawing") back to the UI over the callback.
                    stageListener = { stage -> runCatching { stageCallback?.onStage(stage) } }
                }
                val result = runtime.render(req)
                val bmp = result.nativeBitmap as? Bitmap
                    ?: return@runCatching "err\t${result.error ?: "no image"}"
                writePixels(bmp, File(outFile!!))
                // Hand the captured view hierarchy back as a text sidecar next to the pixels (control over
                // Binder, bulk on the shared filesystem), for the UI's hierarchy + tap-to-inspect panels.
                result.viewTree?.let { tree ->
                    runCatching { File("$outFile.tree").writeText(PreviewViewTreeCodec.encode(tree)) }
                }
                "ok\t${bmp.width}\t${bmp.height}"
            }.getOrElse { "err\t${it.javaClass.simpleName}: ${it.message ?: ""}".trim() }
        }
    }

    /** Write the bitmap's raw ARGB_8888 pixels (width*height*4 bytes) to [out] for the UI to map back. */
    private fun writePixels(bmp: Bitmap, out: File) {
        val buf = ByteBuffer.allocate(bmp.width * bmp.height * 4)
        bmp.copyPixelsToBuffer(buf)
        out.parentFile?.mkdirs()
        out.outputStream().use { it.write(buf.array()) }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
