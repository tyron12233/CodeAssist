package dev.ide.preview.layoutlib

import dev.ide.preview.impl.RealViewRequest
import dev.ide.preview.impl.RealViewResult
import dev.ide.preview.impl.RealViewRuntime
import java.io.File

/**
 * The IDE's real-view seam, answered by layoutlib.
 *
 * On a device the seam is answered by the phone's own framework over an aapt2-linked resource table. A host
 * with no framework (the desktop IDE, the JVM embedded in the iOS app) registers this one, and the rest of the
 * real-view path, from the source mapping of the captured tree to the designer on top of it, runs unchanged.
 */
class LayoutlibRealViewRuntime(
    home: File,
    /** Where resolved resource tables are kept between renders and launches. */
    private val cacheDir: File,
    /** Where layoutlib's native libraries are, when not in the home's own `native` folder. */
    nativeDir: File = File(home, "native"),
) : RealViewRuntime {

    private val renderer = LayoutlibRenderer(LayoutlibHome(home, nativeDir))

    override val readsSourceResources: Boolean get() = true

    override fun render(request: RealViewRequest): RealViewResult {
        val layout = request.layoutFile?.toFile()
            ?: return RealViewResult(null, error = "the request carries no layout file")
        request.stageListener?.invoke("Rendering")
        val result = renderer.render(
            LayoutlibRequest(
                layoutFile = layout,
                resourceDirs = request.resourceDirs.map { it.toFile() },
                classpath = request.classpath.map { it.toFile() },
                resourcePackages = request.resourcePackages.ifEmpty { listOf(request.packageName) },
                themeName = request.themeName ?: DEFAULT_THEME,
                widthPx = request.widthPx,
                heightPx = request.heightPx,
                density = request.density,
                night = request.night,
                rtl = request.rtl,
                minSdk = request.minApi,
                cacheDir = cacheDir,
                builtResourceIds = request.builtResourceIds?.toFile(),
            ),
        )
        val pixels = result.pixels
            ?: return RealViewResult(null, error = result.error ?: "nothing was drawn", problems = result.problems)
        unpremultiply(pixels)
        return RealViewResult(
            pngBytes = null, width = result.width, height = result.height, argb = pixels,
            viewTree = result.viewTree, problems = result.problems,
        )
    }

    /**
     * layoutlib draws premultiplied; the seam's packed pixels are not. Almost every pixel of a rendered screen
     * is opaque, where the two are the same, so only the translucent ones are touched.
     */
    private fun unpremultiply(pixels: IntArray) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            if (a == 255 || a == 0) continue
            val r = (((c shr 16) and 0xff) * 255 / a).coerceAtMost(255)
            val g = (((c shr 8) and 0xff) * 255 / a).coerceAtMost(255)
            val b = ((c and 0xff) * 255 / a).coerceAtMost(255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private companion object {
        /** What a manifest with no theme gets on a current device. */
        const val DEFAULT_THEME = "android:Theme.DeviceDefault.Light.NoActionBar"
    }
}
