package dev.ide.android.support.icons

import dev.ide.android.support.preview.FillRule
import dev.ide.android.support.preview.StrokeCap
import dev.ide.android.support.preview.StrokeJoin
import dev.ide.android.support.preview.VectorGroup
import dev.ide.android.support.preview.VectorNode
import dev.ide.android.support.preview.VectorPath
import dev.ide.android.support.preview.VectorSpec

/**
 * Serialises a [VectorSpec] back to `res/drawable` VectorDrawable XML: the counterpart to
 * `DrawablePreviewParser`, so an imported SVG (or a generated app-icon layer) round-trips through the same
 * model the preview renders. Attributes are written only when they differ from the platform default, which
 * keeps generated files close to what a human would hand-write.
 */
object VectorDrawableWriter {

    private const val NS = "http://schemas.android.com/apk/res/android"

    /**
     * [spec] as VectorDrawable XML. [tint] is written as `android:tint` (a literal `#AARRGGBB` or a
     * `@color/…` reference) for a single-colour icon that should follow a theme colour instead of baking one.
     * [autoMirrored] adds `android:autoMirrored` for icons that should flip in RTL layouts.
     */
    fun write(spec: VectorSpec, tint: String? = null, autoMirrored: Boolean = false): String {
        val sb = StringBuilder(256)
        sb.append("<vector xmlns:android=\"").append(NS).append("\"\n")
        sb.append("    android:width=\"").append(num(spec.widthDp)).append("dp\"\n")
        sb.append("    android:height=\"").append(num(spec.heightDp)).append("dp\"\n")
        sb.append("    android:viewportWidth=\"").append(num(spec.viewportWidth)).append("\"\n")
        sb.append("    android:viewportHeight=\"").append(num(spec.viewportHeight)).append('"')
        if (spec.rootAlpha != 1f) sb.append("\n    android:alpha=\"").append(num(spec.rootAlpha)).append('"')
        if (tint != null) sb.append("\n    android:tint=\"").append(tint).append('"')
        if (autoMirrored) sb.append("\n    android:autoMirrored=\"true\"")
        sb.append(">\n")
        for (node in spec.nodes) writeNode(sb, node, indent = 1)
        sb.append("</vector>\n")
        return sb.toString()
    }

    private fun writeNode(sb: StringBuilder, node: VectorNode, indent: Int) {
        when (node) {
            is VectorPath -> writePath(sb, node, indent)
            is VectorGroup -> writeGroup(sb, node, indent)
        }
    }

    private fun writeGroup(sb: StringBuilder, g: VectorGroup, indent: Int) {
        val pad = "    ".repeat(indent)
        val attrs = ArrayList<String>(7)
        if (g.rotation != 0f) attrs += "android:rotation=\"${num(g.rotation)}\""
        if (g.pivotX != 0f) attrs += "android:pivotX=\"${num(g.pivotX)}\""
        if (g.pivotY != 0f) attrs += "android:pivotY=\"${num(g.pivotY)}\""
        if (g.scaleX != 1f) attrs += "android:scaleX=\"${num(g.scaleX)}\""
        if (g.scaleY != 1f) attrs += "android:scaleY=\"${num(g.scaleY)}\""
        if (g.translateX != 0f) attrs += "android:translateX=\"${num(g.translateX)}\""
        if (g.translateY != 0f) attrs += "android:translateY=\"${num(g.translateY)}\""

        sb.append(pad).append("<group")
        appendAttrs(sb, attrs, pad)
        sb.append(">\n")
        g.clipPathData?.let {
            sb.append(pad).append("    <clip-path android:pathData=\"").append(escape(it)).append("\"/>\n")
        }
        for (child in g.children) writeNode(sb, child, indent + 1)
        sb.append(pad).append("</group>\n")
    }

    private fun writePath(sb: StringBuilder, p: VectorPath, indent: Int) {
        val pad = "    ".repeat(indent)
        val attrs = ArrayList<String>(10)
        attrs += "android:pathData=\"${escape(p.pathData)}\""
        p.fillColor?.let { attrs += "android:fillColor=\"${hex(it)}\"" }
        if (p.fillAlpha != 1f) attrs += "android:fillAlpha=\"${num(p.fillAlpha)}\""
        if (p.fillRule == FillRule.EVEN_ODD) attrs += "android:fillType=\"evenOdd\""
        p.strokeColor?.let { attrs += "android:strokeColor=\"${hex(it)}\"" }
        if (p.strokeWidthVp != 0f) attrs += "android:strokeWidth=\"${num(p.strokeWidthVp)}\""
        if (p.strokeAlpha != 1f) attrs += "android:strokeAlpha=\"${num(p.strokeAlpha)}\""
        if (p.strokeCap != StrokeCap.BUTT) attrs += "android:strokeLineCap=\"${p.strokeCap.name.lowercase()}\""
        if (p.strokeJoin != StrokeJoin.MITER) attrs += "android:strokeLineJoin=\"${p.strokeJoin.name.lowercase()}\""
        if (p.strokeMiter != 4f) attrs += "android:strokeMiterLimit=\"${num(p.strokeMiter)}\""

        sb.append(pad).append("<path")
        appendAttrs(sb, attrs, pad)
        sb.append("/>\n")
    }

    /** One attribute stays on the tag line; several go one per line, indented under it (AGP's own style). */
    private fun appendAttrs(sb: StringBuilder, attrs: List<String>, pad: String) {
        if (attrs.isEmpty()) return
        if (attrs.size == 1) {
            sb.append(' ').append(attrs[0])
            return
        }
        for (a in attrs) sb.append('\n').append(pad).append("    ").append(a)
    }

    /** `0xAARRGGBB` → `#RRGGBB` when fully opaque, else `#AARRGGBB` (what aapt and Studio emit). */
    internal fun hex(argb: Long): String {
        val a = ((argb shr 24) and 0xFF).toInt()
        val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
        return if (a == 0xFF) "#$rgb" else "#" + a.toString(16).padStart(2, '0').uppercase() + rgb
    }

    private fun num(v: Float): String = SvgPathData.n(v)

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
