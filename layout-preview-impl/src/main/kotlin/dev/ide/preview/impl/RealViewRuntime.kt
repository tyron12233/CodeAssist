package dev.ide.preview.impl

import java.nio.file.Path

/**
 * Inputs to render a layout with the REAL Android view stack on device — the "layoutlib-on-device" path. The
 * runtime builds a real `Context`/`Resources` over the build's aapt2-linked [resourcesAp], loads the project's
 * libraries (+ [resourcesAp]'s matching `R.jar`, carried on [classpath]) via a `DexClassLoader`, inflates the
 * layout with the framework's real `LayoutInflater`, and draws the resulting view tree to a bitmap.
 *
 * [resourcesAp] is a relink of the build's compiled resources with the live editor buffer (done by the host
 * before the request), so an edited or newly added layout renders and resolves against the arsc + library R.
 * Android-free (paths + primitives) so the port stays in the shared module; the device tooling (android.jar,
 * D8, cache dir, device API level) is held by the runtime impl, not threaded here.
 */
class RealViewRequest(
    /** The layout resource name (e.g. `activity_main`) — looked up in the (relinked) resources by id. */
    val layoutName: String,
    /** The live editor buffer. The runtime inflates by id from [resourcesAp] (already relinked with this text),
     *  so the field is informational; kept for diagnostics and a future in-runtime relink. */
    val layoutText: String,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val night: Boolean,
    /** The `resources.ap_` to render against (binary manifest + `resources.arsc` + compiled res XML): the
     *  build's compiled resources relinked with [layoutText], or the build's own if the relink was unavailable. */
    val resourcesAp: Path,
    /** What to load onto the `DexClassLoader`, as a mixed list the runtime splits by kind:
     *  - library `.jar`s (framework/library classes) → dexed via the shared cache + merged (stable layer),
     *  - the `R.jar` (`R.jar`/`preview-r.jar`, matched by name) → the volatile R layer,
     *  - the project's own PRE-DEXED `.dex` files (the build's `project-dex`/`lib-dex`, app + module code) →
     *    added directly (no re-dex) so a project-source custom view resolves at inflate time. */
    val classpath: List<Path>,
    /** The app package (the `R` package the linked resources live under). */
    val packageName: String,
    /** The activity theme style name (for `?attr/…` resolution), or null. */
    val themeName: String?,
    /** The module's `minSdk` — the dex min-api, so the preview's shared-cache buckets match the build's
     *  (which keys the cache by min-api); using the device api instead would key a separate, unshared cache. */
    val minApi: Int = 21,
    /** When true, non-framework view classes on [classpath] are INTERPRETED (the `:jvm-interp` VM) instead of
     *  dexed onto a `DexClassLoader` — the runtime builds no dex and loads no downloaded/library/user code into
     *  ART. [classpath] may then include class DIRECTORIES (the build's compiled `.class` output) as well as
     *  jars, and no `.dex` files. */
    val interpretClasses: Boolean = false,
) {
    /** Transient in-process callback for fine render stages ("Dexing"/"Inflating"/"Drawing"), for the status
     *  chip. NOT serialized across the `:preview` IPC — each side sets its own (the daemon forwards over the
     *  AIDL stage callback; the caller relays those onto this). */
    var stageListener: ((String) -> Unit)? = null
}

/**
 * A real-view render result: the rendered image + measured pixel size, or an [error] (→ caller falls back to
 * owned rendering). On device the image is returned as a live [nativeBitmap] (an `android.graphics.Bitmap`,
 * held as `Any?` so the port stays android-free) for the same-process UI to wrap with no PNG round-trip;
 * [pngBytes] is the portable form (used when no native bitmap is available).
 */
class RealViewResult(
    val pngBytes: ByteArray?,
    val width: Int = 0,
    val height: Int = 0,
    val error: String? = null,
    val nativeBitmap: Any? = null,
    /** The captured hierarchy of the real inflated view tree (class/id/bounds/attributes per node), or null
     *  when the runtime didn't snapshot it. Carried up to [dev.ide.preview.LayoutPreviewResult.viewTree] so
     *  the Preview view can show the hierarchy + a tap-to-inspect panel over the real-view render. */
    val viewTree: dev.ide.preview.PreviewViewNode? = null,
)

/**
 * Platform seam (device-only) that renders a layout with the real Android framework + the project's real
 * libraries, returning a PNG. Parallel to [CustomViewRuntime]; the device impl lives in `ide-android`
 * (`dev.ide.preview.realview`). Returns null when no runtime is configured (→ the owned-rendering path).
 */
interface RealViewRuntime {
    fun render(request: RealViewRequest): RealViewResult?

    companion object {
        val NONE: RealViewRuntime = object : RealViewRuntime {
            override fun render(request: RealViewRequest): RealViewResult? = null
        }
    }
}
