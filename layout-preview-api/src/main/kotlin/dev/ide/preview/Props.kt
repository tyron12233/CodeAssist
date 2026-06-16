package dev.ide.preview

/**
 * The resolved standard attributes a built-in renderer reads. Layout dimensions use the framework sentinels
 * ([MATCH_PARENT]/[WRAP_CONTENT]) or a positive pixel size. Custom views don't use this — they read their
 * own attributes in their constructor — but the engine still fills the layout/padding/background fields on a
 * custom view's node so the owned base honours `layout_*`, padding, and background (§7 of the design).
 */
class Props {
    var id: String? = null

    var layoutWidth: Int = WRAP_CONTENT
    var layoutHeight: Int = WRAP_CONTENT
    var weight: Float = 0f
    var orientation: Int = HORIZONTAL
    /** This view's `android:layout_gravity` — how it aligns *within its parent*. */
    var gravity: Int = GRAVITY_NONE
    /** This view's own `android:gravity` — how it aligns *its content/children*. */
    var contentGravity: Int = GRAVITY_NONE

    var paddingLeft: Int = 0
    var paddingTop: Int = 0
    var paddingRight: Int = 0
    var paddingBottom: Int = 0

    var marginLeft: Int = 0
    var marginTop: Int = 0
    var marginRight: Int = 0
    var marginBottom: Int = 0

    var backgroundColor: Int? = null
    var visibility: Int = VISIBLE

    // Text widgets
    var text: CharSequence = ""
    var textSizePx: Float = 0f
    var textColor: Int = 0xFF000000.toInt()
    var bold: Boolean = false
    /** 0 = unlimited. `singleLine` maps to 1. */
    var maxLines: Int = 0
    /** Append an ellipsis to the last visible line when text is truncated by [maxLines]. */
    var ellipsize: Boolean = false

    // Image widgets — a @drawable/@mipmap reference, resolved lazily by the renderer.
    var imageRef: String? = null

    /** Anything a renderer needs to stash between passes (e.g. a computed text layout). */
    val extras: MutableMap<String, Any?> = HashMap()

    val hPadding: Int get() = paddingLeft + paddingRight
    val vPadding: Int get() = paddingTop + paddingBottom

    companion object {
        const val MATCH_PARENT = -1
        const val WRAP_CONTENT = -2

        const val HORIZONTAL = 0
        const val VERTICAL = 1

        const val GRAVITY_NONE = 0
        const val GRAVITY_START = 1
        const val GRAVITY_END = 2
        const val GRAVITY_CENTER_HORIZONTAL = 4
        const val GRAVITY_TOP = 8
        const val GRAVITY_BOTTOM = 16
        const val GRAVITY_CENTER_VERTICAL = 32
        const val GRAVITY_CENTER = GRAVITY_CENTER_HORIZONTAL or GRAVITY_CENTER_VERTICAL

        const val VISIBLE = 0
        const val INVISIBLE = 1
        const val GONE = 2
    }
}
