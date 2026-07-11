package dev.ide.preview

/** A plain [RenderContext]: the graphics factory, the resource resolver, and the previewed density. */
class SimpleRenderContext(
    override val gfx: RGraphics,
    override val res: PreviewResources,
    override val density: Float = 1f,
    override val scaledDensity: Float = density,
) : RenderContext

/**
 * The owned measure → layout → draw driver. Snapshot and live mode share the same passes over the same tree
 * (§8.4) — only the [RCanvas] differs (a bitmap-backed canvas vs. a live surface). The engine, not any
 * framework view tree, recurses; each container renderer drives its own children. Lives in the api (it uses
 * only api types) so the Compose UI layer can drive a [RenderNode] tree handed to it by the backend.
 */
class PreviewEngine(val ctx: RenderContext) {

    /** Measure (width exact, height bounded) then position the tree at the origin. */
    fun measureAndLayout(root: RenderNode, widthPx: Int, heightPx: Int) {
        root.renderer.measure(root, MeasureSpec.exactly(widthPx), MeasureSpec.atMost(heightPx), ctx)
        root.renderer.layout(root, 0, 0, root.measured.width, root.measured.height, ctx)
    }

    fun draw(root: RenderNode, canvas: RCanvas) {
        root.renderer.draw(root, canvas, ctx)
    }

    /** Full pass into [canvas]; returns the measured root size (e.g. to size the snapshot bitmap). */
    fun render(root: RenderNode, widthPx: Int, heightPx: Int, canvas: RCanvas): Size {
        measureAndLayout(root, widthPx, heightPx)
        draw(root, canvas)
        return root.measured
    }

    /** Deepest node whose laid-out bounds contain ([x],[y]) — for selection/hit-testing in the editor. */
    fun hitTest(root: RenderNode, x: Float, y: Float): RenderNode? {
        if (x < root.left || x >= root.right || y < root.top || y >= root.bottom) return null
        for (i in root.children.indices.reversed()) {
            hitTest(root.children[i], x, y)?.let { return it }
        }
        return root
    }
}

/**
 * What the backend hands the UI layer to render: the inflated [root] tree (resources resolved, custom views
 * instantiated), the [resources] (for image loading at draw time), and the previewed device density. The UI
 * builds a [RenderContext] with its own platform [RGraphics] (real text metrics) and drives [PreviewEngine]
 * over this tree into a Compose-backed [RCanvas].
 */
class LayoutPreviewResult(
    val root: RenderNode,
    val resources: PreviewResources,
    val density: Float,
    val scaledDensity: Float,
    /** Resolve a `@drawable/@mipmap` reference to its backing file path; the UI decodes it to an [RImage]. */
    val imageFile: (ref: String) -> String? = { null },
    /** Nodes that couldn't be rendered as intended (unknown tag, unresolved include, custom view) — surfaced in the pane. */
    val problems: List<PreviewProblem> = emptyList(),
    /**
     * A complete PNG of the layout rendered with the REAL Android view stack (the on-device "layoutlib" path),
     * or null for the owned-rendering path. When present the UI shows this image instead of driving
     * [PreviewEngine] over [root] (which is then a minimal/empty tree). Device-only; desktop never sets it.
     */
    val renderedImage: ByteArray? = null,
    /**
     * The same real-view render as a live native image (an `android.graphics.Bitmap`, held as `Any?` so the
     * api stays android-free) — set on device instead of [renderedImage] so the same-process UI wraps it with
     * no PNG encode/decode. The UI prefers this over [renderedImage] when present; desktop never sets it.
     */
    val renderedNativeImage: Any? = null,
    /**
     * The captured hierarchy of the REAL inflated view tree (device "layoutlib" path), or null for the
     * owned-rendering path (where [root] already carries the geometry). When present the UI drives its
     * component tree + tap-to-select + inspector off THIS tree instead of [root] (bounds line up with the
     * rendered image). Device-only; desktop never sets it.
     */
    val viewTree: PreviewViewNode? = null,
    /**
     * Real-view (device) only: true when the layout can't render because the project's libraries aren't dexed
     * yet — the one-time, expensive step the preview no longer does silently. The UI shows a "prepare libraries"
     * prompt (with [undexedCount] the number of not-yet-dexed libraries) and a build action instead of rendering;
     * [root]/[renderedImage] may still carry an owned fallback behind the prompt. False once every library is
     * dexed (a fresh dependency flips it back true until the next prepare).
     */
    var buildRequired: Boolean = false,
    var undexedCount: Int = 0,
)

/** A node the preview couldn't render as intended; [tag] is the widget/structural tag, [message] the reason. */
data class PreviewProblem(val tag: String, val message: String)

/** The previewed device configuration the UI requests: viewport pixels, density, and config toggles. */
data class PreviewRequest(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val showChrome: Boolean = true,
    val night: Boolean = false,
    /** Request the on-device real-view ("layoutlib") render; the backend falls back to owned rendering if it can't. */
    val realViews: Boolean = false,
)

/**
 * The backend capability the preview pane needs, kept in the api so both the engine host (ide-core) and the
 * Compose UI layer can name [LayoutPreviewResult] without ide-ui taking an android-laden dependency. The UI
 * casts its `IdeBackend` to this; a host without Android support simply doesn't implement it.
 */
interface LayoutPreviewBackend {
    suspend fun layoutPreview(path: String, text: String, request: PreviewRequest): LayoutPreviewResult?

    /**
     * Render a self-contained layout [xml] with the owned engine (built-in + Material widgets + optional system
     * chrome), independent of any open project — the Learn tab's Android lessons drive this to visualize the
     * layout they teach with no project context. Always owned rendering (never the real-view/SDK path); keep
     * the lesson XML self-contained (project `@string`/`@color`/… references won't resolve against the empty
     * resource table). Null when the host has no Android support wired.
     */
    suspend fun layoutPreviewStandalone(xml: String, request: PreviewRequest): LayoutPreviewResult? = null
}
