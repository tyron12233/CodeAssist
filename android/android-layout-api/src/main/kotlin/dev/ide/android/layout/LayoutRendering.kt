package dev.ide.android.layout

import dev.ide.platform.ServiceKey
import kotlinx.coroutines.flow.StateFlow

/**
 * Renders Android layout XML the way the app would draw it.
 *
 * The host renders with the real Android framework where it can (on a device, the project's own libraries
 * inflated and drawn by the framework) and falls back to its own approximate renderer where it cannot (the
 * desktop host, or before the project's libraries are prepared). Either way the answer has the same shape: an
 * image, and the view hierarchy that produced it with each view's bounds in that image's pixels and, where the
 * view came from an element of the buffer, that element's offset. That pairing is what a design surface needs:
 * draw the image, hit-test the tree, and map a tap back to the XML.
 *
 * Resolve it from a plugin's engine facet or, in a UI body, through `UiContext.service(LAYOUT_RENDERER)`.
 * Registered per open project; null when no project is open or the host has no Android support.
 */
interface LayoutRenderer {

    /** The stage of the render in flight ("Linking resources", "Rendering"), or null when idle. */
    val progress: StateFlow<String?>

    /**
     * Changes whenever something the render depends on, other than the buffer, changes: the project's own views
     * were compiled, a build finished, its libraries were prepared. A surface that renders on every edit also
     * renders when this changes, or it keeps showing a frame made from inputs that are gone.
     */
    val inputs: StateFlow<Any?> get() = NO_INPUT_CHANGES

    /**
     * Render [request]. Runs off the caller's thread; a newer request does not cancel an older one, so a caller
     * rendering on every keystroke debounces and cancels its own coroutine.
     */
    suspend fun render(request: LayoutRenderRequest): LayoutRenderResult

    /**
     * Start the one-time build that prepares the project's libraries for real rendering, for a result whose
     * [LayoutRenderResult.librariesNeedPreparing] is set. Returns false when no build could be started (the
     * file is not in a module). Progress is the IDE's own build console; render again when it finishes.
     */
    fun prepareLibraries(path: String): Boolean
}

private val NO_INPUT_CHANGES: StateFlow<Any?> = kotlinx.coroutines.flow.MutableStateFlow(null)

/** The project's layout renderer. See [LayoutRenderer]. */
val LAYOUT_RENDERER: ServiceKey<LayoutRenderer> = ServiceKey("android.layoutRenderer")

/**
 * One render: the layout file at [path] with the buffer [text] (which may be ahead of the file on disk), on a
 * screen [widthPx] × [heightPx] at [density] (pixels per dp). [night] selects the `-night` resources and the
 * theme's dark variant; [showSystemUi] draws the status bar and app bar around the content, as the running
 * app would show it.
 */
class LayoutRenderRequest(
    val path: String,
    val text: String,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val night: Boolean = false,
    val showSystemUi: Boolean = true,
    /**
     * How large to draw the image relative to the screen (0 < scale <= 1). The layout is still measured at
     * [widthPx] x [heightPx] and the tree's bounds stay in those pixels; only the picture is smaller. A surface
     * showing the screen zoomed out asks for about what it can show, and asks again when the user zooms in.
     * A host may ignore it and draw at full size.
     */
    val scale: Float = 1f,
)

/**
 * What a render produced.
 *
 * [image] is null only when nothing could be drawn at all (then [resourceErrors] or [problems] say why). [root]
 * is the hierarchy that was drawn, including whatever the framework built around the layout (window decor, an
 * app bar); only nodes with a [LayoutViewNode.sourceOffset] came from the buffer.
 *
 * [resourceErrors] are errors in the project's resources that stopped the layout from linking at all; the
 * caller keeps showing its previous render behind them, because a half-typed edit should not blank the
 * preview. [problems] are softer: a view that could not be created, an include that did not resolve.
 *
 * [librariesNeedPreparing] means the real renderer cannot run until the project's libraries are prepared once
 * ([LayoutRenderer.prepareLibraries]); [image] is then the approximate render. [approximate] is true whenever
 * the image came from the host's own renderer rather than the Android framework.
 */
class LayoutRenderResult(
    val image: LayoutImage?,
    val root: LayoutViewNode?,
    val problems: List<LayoutProblem> = emptyList(),
    val resourceErrors: List<LayoutResourceError> = emptyList(),
    val librariesNeedPreparing: Boolean = false,
    val unpreparedLibraryCount: Int = 0,
    val approximate: Boolean = false,
    /** The screen the tree's bounds are in, in pixels; [image] may be smaller (see [LayoutRenderRequest.scale]). */
    val widthPx: Int = image?.width ?: 0,
    val heightPx: Int = image?.height ?: 0,
)

/**
 * A rendered frame, [width] × [height] pixels, in whichever form the host produced it, most direct first.
 *
 * [bitmap] is a platform image the host drew into, handed over without a copy: an `android.graphics.Bitmap` on
 * an Android host. [argb] is packed ARGB pixels, row-major. [png] is an encoded image. At least one is set; a
 * consumer takes the first form it can use.
 */
class LayoutImage(
    val width: Int,
    val height: Int,
    val bitmap: Any? = null,
    val argb: IntArray? = null,
    val png: ByteArray? = null,
)

/**
 * One view of a rendered hierarchy. [className] is the class the framework actually created (a
 * `MaterialButton` for a `<Button>` under a Material theme), [id] the resource-id entry name (`submit` for
 * `@+id/submit`). Bounds are pixels in the render's image, from its top-left.
 *
 * [sourceOffset] is the start of the `<Tag` this view was inflated from in the request's buffer, or null when
 * the view does not correspond to an element of it (window decor, a view a library created internally, the
 * body of an `<include>`d layout). [properties] are what the framework reports about the view, for display.
 */
class LayoutViewNode(
    val className: String,
    val id: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val sourceOffset: Int?,
    val properties: List<LayoutViewProperty> = emptyList(),
    val children: List<LayoutViewNode> = emptyList(),
) {
    val simpleName: String get() = className.substringAfterLast('.')
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** The deepest node (or this one) whose bounds contain ([x], [y]); null when the point is outside. */
    fun hitTest(x: Float, y: Float): LayoutViewNode? {
        if (x < left || x >= right || y < top || y >= bottom) return null
        for (i in children.indices.reversed()) children[i].hitTest(x, y)?.let { return it }
        return this
    }

    /** This node and every descendant, parents first. */
    fun walk(): Sequence<LayoutViewNode> = sequence {
        yield(this@LayoutViewNode)
        for (c in children) yieldAll(c.walk())
    }
}

/** One reported property of a rendered view: [group] buckets it for display (`Layout`, `Text`, ...). */
class LayoutViewProperty(val group: String, val name: String, val value: String)

/** Something that went wrong rendering one view: [tag] names it when known. */
class LayoutProblem(val tag: String?, val message: String)

/** An error in a resource file that stopped the layout from linking, at [file]:[line] when known. */
class LayoutResourceError(val file: String?, val line: Int?, val message: String)
