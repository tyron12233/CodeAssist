package dev.ide.preview.realview

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.ide.jvm.interpretedClassNameOf
import dev.ide.preview.PreviewViewNode
import dev.ide.preview.PreviewViewProperty
import kotlin.math.roundToInt

/**
 * Snapshots the REAL inflated view tree (after measure/layout) into an android-free [PreviewViewNode] tree for
 * the Preview view's hierarchy + tap-to-inspect panels. Framework view classes (TextView, ImageView,
 * LinearLayout, …) load from the boot classloader — the same `Class` objects this code compiles against — so
 * `is TextView` matches even for a library/user subclass (a MaterialButton is-a AppCompatButton is-a TextView),
 * and the type-specific attribute extraction below works across the whole tree.
 *
 * Bounds are captured as pixels relative to the render root's origin (accumulating each `ViewGroup`'s offset and
 * scroll) so they line up 1:1 with the rendered bitmap — the UI hit-tests taps and draws the selection outline
 * directly against them. Everything runs under a `runCatching` at the call site, so a capture failure only
 * drops the hierarchy (the render still returns its bitmap).
 */
object ViewHierarchyCapture {

    /** Hard cap so a pathological tree can't blow up the snapshot / the IPC payload. */
    private const val MAX_NODES = 4000

    fun capture(root: View): PreviewViewNode {
        val dm = root.resources.displayMetrics
        val density = if (dm.density > 0f) dm.density else 1f
        val scaledDensity = density * root.resources.configuration.fontScale.let { if (it > 0f) it else 1f }
        val budget = intArrayOf(MAX_NODES)
        return node(root, 0, 0, density, scaledDensity, budget)
    }

    private fun node(v: View, offX: Int, offY: Int, density: Float, scaledDensity: Float, budget: IntArray): PreviewViewNode {
        val absL = offX + v.left
        val absT = offY + v.top
        val absR = absL + v.width
        val absB = absT + v.height

        val children = ArrayList<PreviewViewNode>()
        if (v is ViewGroup) {
            val childOffX = absL - v.scrollX
            val childOffY = absT - v.scrollY
            var i = 0
            while (i < v.childCount && budget[0] > 0) {
                budget[0]--
                children.add(node(v.getChildAt(i), childOffX, childOffY, density, scaledDensity, budget))
                i++
            }
        }
        return PreviewViewNode(
            // An interpreted view is a generated peer whose Class name is synthetic (dev.ide.jvm.peers.*); report
            // the interpreted class it stands for so the hierarchy shows the real tag (com.example.MyView).
            className = interpretedClassNameOf(v) ?: v.javaClass.name,
            id = resourceId(v),
            left = absL, top = absT, right = absR, bottom = absB,
            properties = properties(v, density, scaledDensity),
            children = children,
        )
    }

    /** The view's resource-id entry name (`submit` for `@id/submit`), or null for `NO_ID` / a generated id. */
    private fun resourceId(v: View): String? {
        val id = v.id
        if (id == View.NO_ID) return null
        return runCatching { v.resources.getResourceEntryName(id) }.getOrNull()
    }

    private fun properties(v: View, density: Float, scaledDensity: Float): List<PreviewViewProperty> {
        val out = ArrayList<PreviewViewProperty>()
        fun add(group: String, name: String, value: String?) {
            if (!value.isNullOrEmpty()) out.add(PreviewViewProperty(group, name, value.take(160)))
        }
        fun dp(px: Int) = "${(px / density).roundToInt()}dp"

        // ---- Layout ----
        val lp = v.layoutParams
        if (lp != null) {
            add("Layout", "layout_width", lpSize(lp.width, density))
            add("Layout", "layout_height", lpSize(lp.height, density))
        }
        if (lp is ViewGroup.MarginLayoutParams &&
            (lp.leftMargin != 0 || lp.topMargin != 0 || lp.rightMargin != 0 || lp.bottomMargin != 0)) {
            add("Layout", "layout_margin", "${dp(lp.leftMargin)} ${dp(lp.topMargin)} ${dp(lp.rightMargin)} ${dp(lp.bottomMargin)}")
        }
        if (lp is LinearLayout.LayoutParams) {
            if (lp.weight != 0f) add("Layout", "layout_weight", trimFloat(lp.weight))
            if (lp.gravity != -1) add("Layout", "layout_gravity", gravityString(lp.gravity))
        } else if (lp is FrameLayout.LayoutParams && lp.gravity != -1) {
            add("Layout", "layout_gravity", gravityString(lp.gravity))
        }
        add("Layout", "position", "${dp(v.left)}, ${dp(v.top)}")
        add("Layout", "size", "${dp(v.width)} × ${dp(v.height)}")
        if (v.paddingLeft != 0 || v.paddingTop != 0 || v.paddingRight != 0 || v.paddingBottom != 0) {
            add("Layout", "padding", "${dp(v.paddingLeft)} ${dp(v.paddingTop)} ${dp(v.paddingRight)} ${dp(v.paddingBottom)}")
        }

        // ---- Appearance ----
        when (v.visibility) {
            View.INVISIBLE -> add("Appearance", "visibility", "invisible")
            View.GONE -> add("Appearance", "visibility", "gone")
        }
        if (v.alpha != 1f) add("Appearance", "alpha", trimFloat(v.alpha))
        add("Appearance", "background", drawableDesc(v.background))
        if (v.elevation != 0f) add("Appearance", "elevation", dp(v.elevation.roundToInt()))
        if (v.rotation != 0f) add("Appearance", "rotation", "${trimFloat(v.rotation)}°")
        if (v.scaleX != 1f || v.scaleY != 1f) add("Appearance", "scale", "${trimFloat(v.scaleX)} × ${trimFloat(v.scaleY)}")
        if (v.translationX != 0f || v.translationY != 0f) add("Appearance", "translation", "${dp(v.translationX.roundToInt())}, ${dp(v.translationY.roundToInt())}")

        // ---- Type-specific ----
        when (v) {
            is TextView -> textProps(v, ::add, scaledDensity)
            is ImageView -> {
                add("Image", "scaleType", v.scaleType?.name?.lowercase())
                add("Image", "src", drawableDesc(v.drawable))
            }
        }
        if (v is LinearLayout) {
            add("Layout", "orientation", if (v.orientation == LinearLayout.VERTICAL) "vertical" else "horizontal")
            if (v.weightSum > 0f) add("Layout", "weightSum", trimFloat(v.weightSum))
        }
        if (v is CompoundButton) add("State", "checked", v.isChecked.toString())
        if (v is ProgressBar) {
            add("State", "indeterminate", v.isIndeterminate.toString())
            if (!v.isIndeterminate) add("State", "progress", "${v.progress} / ${v.max}")
        }

        // ---- Behavior ----
        if (!v.isEnabled) add("Behavior", "enabled", "false")
        if (v.isClickable) add("Behavior", "clickable", "true")
        v.contentDescription?.toString()?.let { add("Behavior", "contentDescription", it) }

        return out
    }

    private inline fun textProps(v: TextView, add: (String, String, String?) -> Unit, scaledDensity: Float) {
        v.text?.toString()?.let { if (it.isNotEmpty()) add("Text", "text", it) }
        v.hint?.toString()?.let { if (it.isNotEmpty()) add("Text", "hint", it) }
        add("Text", "textSize", "${(v.textSize / scaledDensity).roundToInt()}sp")
        add("Text", "textColor", colorHex(v.currentTextColor))
        add("Text", "gravity", gravityString(v.gravity))
        val style = v.typeface?.style ?: 0
        val styleStr = when {
            style and android.graphics.Typeface.BOLD_ITALIC == android.graphics.Typeface.BOLD_ITALIC -> "bold|italic"
            style and android.graphics.Typeface.BOLD != 0 -> "bold"
            style and android.graphics.Typeface.ITALIC != 0 -> "italic"
            else -> null
        }
        add("Text", "textStyle", styleStr)
        if (v.maxLines in 1..99) add("Text", "maxLines", v.maxLines.toString())
        v.ellipsize?.let { add("Text", "ellipsize", it.name.lowercase()) }
        if (v is EditText && v.inputType != InputType.TYPE_NULL) add("Text", "inputType", inputTypeString(v.inputType))
    }

    private fun lpSize(v: Int, density: Float): String = when (v) {
        ViewGroup.LayoutParams.MATCH_PARENT -> "match_parent"
        ViewGroup.LayoutParams.WRAP_CONTENT -> "wrap_content"
        else -> "${(v / density).roundToInt()}dp"
    }

    private fun drawableDesc(d: Drawable?): String? = when (d) {
        null -> null
        is ColorDrawable -> colorHex(d.color)
        else -> d.javaClass.simpleName.ifEmpty { d.javaClass.name.substringAfterLast('.') }
    }

    private fun colorHex(argb: Int): String = "#%08X".format(argb)

    /** Drop a trailing `.0` so `1.0f` shows as `1` but `1.5f` stays `1.5`. */
    private fun trimFloat(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()

    private fun gravityString(g: Int): String {
        if (g == 0 || g == -1) return "none"
        val parts = ArrayList<String>(3)
        when {
            g and Gravity.START == Gravity.START -> parts.add("start")
            g and Gravity.END == Gravity.END -> parts.add("end")
            g and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT -> parts.add("left")
            g and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT -> parts.add("right")
            g and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL -> parts.add("center_horizontal")
        }
        when (g and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> parts.add("top")
            Gravity.BOTTOM -> parts.add("bottom")
            Gravity.CENTER_VERTICAL -> parts.add("center_vertical")
        }
        return if (parts.isEmpty()) "0x%X".format(g) else parts.joinToString("|")
    }

    private fun inputTypeString(t: Int): String {
        val cls = t and InputType.TYPE_MASK_CLASS
        return when (cls) {
            InputType.TYPE_CLASS_TEXT -> when (t and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> "textPassword"
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "textEmailAddress"
                InputType.TYPE_TEXT_VARIATION_URI -> "textUri"
                else -> "text"
            }
            InputType.TYPE_CLASS_NUMBER -> "number"
            InputType.TYPE_CLASS_PHONE -> "phone"
            InputType.TYPE_CLASS_DATETIME -> "datetime"
            else -> "0x%X".format(t)
        }
    }
}
