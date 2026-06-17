package dev.ide.preview

/**
 * One node of the owned render tree — a built-in widget, a custom view, or a container. The engine (not any
 * framework view-tree traversal) drives [measure]/[layout]/[draw] over these. For a custom view, [host] is
 * the owned-base instance whose `onMeasure`/`onLayout`/`onDraw` run the user's code; for a built-in it is null
 * and [renderer] does all the work.
 */
class RenderNode(val host: ViewHost? = null) {
    /** The XML tag or fully-qualified class name this node came from (placeholder labels, debugging). */
    var tag: String = ""

    val props: Props = Props()
    val children: MutableList<RenderNode> = ArrayList()
    var renderer: Renderer = Renderer.FALLBACK

    var measured: Size = Size(0, 0)
    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun setBounds(l: Int, t: Int, r: Int, b: Int) {
        left = l; top = t; right = r; bottom = b
    }

    /** Set by the host (live mode); a built-in [invalidate] / a custom view's `invalidate()` routes here. */
    var onDirty: (() -> Unit)? = null

    fun markDirty() {
        onDirty?.invoke()
    }
}

/**
 * The owned view base, abstracted. Both the on-device `BridgeView` (which `extends android.view.View`) and
 * the desktop `android.view.View` shim implement this, so [CustomViewRenderer] drives a custom view the same
 * way on either platform. `drawContent` runs the user view's `onDraw` against the owned [RCanvas] (the device
 * impl unwraps the backing `android.graphics.Canvas` to hand to user code).
 */
interface ViewHost {
    fun measure(widthSpec: Int, heightSpec: Int)
    val measuredWidth: Int
    val measuredHeight: Int
    fun layout(l: Int, t: Int, r: Int, b: Int)
    fun drawContent(canvas: RCanvas)
}
