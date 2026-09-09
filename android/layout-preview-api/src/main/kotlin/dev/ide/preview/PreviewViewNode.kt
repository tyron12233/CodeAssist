package dev.ide.preview

/**
 * A captured node of the REAL-view render's view hierarchy (the on-device "layoutlib" path). Unlike the owned
 * [RenderNode] tree — which the UI drives itself — the real-view path inflates + draws with the actual Android
 * framework and returns only a bitmap; this snapshots that inflated `View` tree so the Preview view can show
 * the hierarchy and let the user tap a view to inspect it, without shipping a live `View` (or any android type)
 * up to the UI. Plain data (android-free) so it rides on [LayoutPreviewResult].
 *
 * [left]/[top]/[right]/[bottom] are laid-out pixels RELATIVE to the render root's origin (0,0 = top-left of the
 * rendered bitmap), so they line up 1:1 with the returned image for hit-testing and the selection outline.
 * [properties] are read-only inspected attributes grouped for display (Layout / Appearance / Text / …).
 *
 * [sourceOffset] is the start offset of the `<Tag …>` this view was inflated from, in the live layout XML
 * buffer — filled in by the host after render (by aligning this captured tree to the parsed source), or null
 * when the view can't be traced back to an editable element (window-decor chrome, framework-synthesized
 * internals, an `<include>`d layout's body). A non-null offset is what lets the Preview open the editable
 * attribute editor for the tapped view; null means read-only.
 */
class PreviewViewNode(
    /** The view's fully-qualified class name (e.g. `com.google.android.material.button.MaterialButton`). */
    val className: String,
    /** The view's resource-id entry name (e.g. `submit` for `@id/submit`), or null when it has no id. */
    val id: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val properties: List<PreviewViewProperty>,
    val children: List<PreviewViewNode>,
    val sourceOffset: Int? = null,
) {
    /** The class's simple name for compact display (`MaterialButton`). */
    val simpleName: String get() = className.substringAfterLast('.')
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** Deepest descendant (or `this`) whose laid-out bounds contain ([x],[y]); null if the point is outside
     *  this node. Mirrors [PreviewEngine.hitTest] for the captured hierarchy (device px from the root origin). */
    fun hitTest(x: Float, y: Float): PreviewViewNode? {
        if (x < left || x >= right || y < top || y >= bottom) return null
        for (i in children.indices.reversed()) children[i].hitTest(x, y)?.let { return it }
        return this
    }
}

/**
 * One inspected attribute of a [PreviewViewNode]: [group] buckets it for display (e.g. `Layout`, `Appearance`,
 * `Text`), [name] is the attribute name (`layout_width`, `textColor`, …) and [value] its resolved display
 * value. Read-only — the real-view inspector shows these but does not edit the source (yet).
 */
class PreviewViewProperty(val group: String, val name: String, val value: String)
