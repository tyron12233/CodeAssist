package dev.ide.android.support.icons

import dev.ide.android.support.preview.AndroidColor
import dev.ide.android.support.preview.FillRule
import dev.ide.android.support.preview.StrokeCap
import dev.ide.android.support.preview.StrokeJoin
import dev.ide.android.support.preview.VectorGroup
import dev.ide.android.support.preview.VectorNode
import dev.ide.android.support.preview.VectorPath
import dev.ide.android.support.preview.VectorSpec
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.sqrt

/** How an SVG should be turned into a VectorDrawable. */
data class SvgConvertOptions(
    /** Intrinsic width in dp; null keeps the SVG's own `width` (or its viewBox width, or 24). */
    val widthDp: Float? = null,
    /** Intrinsic height in dp; null keeps the SVG's own `height` (or its viewBox height, or 24). */
    val heightDp: Float? = null,
    /**
     * Repaint every fill and stroke this `0xAARRGGBB` colour. This is how a single-colour icon gets
     * recoloured on import; leave it null to keep a multicolour illustration's own palette.
     */
    val overrideColor: Long? = null,
)

/**
 * The outcome of a conversion: the [spec] (render-ready, so the importer can preview exactly what it is about
 * to write) and any [warnings] describing what the SVG asked for that a VectorDrawable cannot express.
 */
data class SvgConversion(val spec: VectorSpec, val warnings: List<String>)

/**
 * Converts an SVG document into a VectorDrawable, as [VectorSpec] (preview-ready) or XML text.
 *
 * VectorDrawable is a deliberate subset of SVG, so the conversion is mostly about lowering the parts that
 * have no equivalent:
 *  - **Shapes**: `<rect>`/`<circle>`/`<ellipse>`/`<line>`/`<polygon>`/`<polyline>` become `pathData`, since
 *    `<path>` is the only geometry a VectorDrawable has.
 *  - **Transforms**: a `<g transform>` chain that is only scale + translate maps onto a `<group>` and the
 *    original `pathData` is preserved byte for byte. A rotation, `skew` or general `matrix` cannot be nested
 *    like that, so those are *baked into the coordinates* (see [SvgPathData]).
 *  - **Styling**: presentation attributes and inline `style="…"` are resolved with inheritance down the
 *    tree, including `opacity`, `fill-rule`, and `currentColor`.
 *  - **Paint servers**: a gradient fill has no `<path>` equivalent, so it degrades to the gradient's first
 *    stop colour and says so in [SvgConversion.warnings].
 *
 * JAXP + stdlib only (namespace-unaware, like the rest of the resource layer), so it runs the same on desktop
 * and on ART.
 */
object SvgToVectorDrawable {

    private const val MAX_DEPTH = 24

    /** [svgText] as VectorDrawable XML, or null when it isn't an SVG document at all. */
    fun convert(svgText: String, options: SvgConvertOptions = SvgConvertOptions()): SvgConversion? =
        toSpec(svgText, options)

    /** [svgText] as a [VectorSpec], or null when the root element isn't `<svg>`. */
    fun toSpec(svgText: String, options: SvgConvertOptions = SvgConvertOptions()): SvgConversion? {
        val root = runCatching {
            builder().parse(svgText.byteInputStream(Charsets.UTF_8)).documentElement
        }.getOrNull() ?: return null
        if (root.tagName.substringAfterLast(':') != "svg") return null

        val warnings = LinkedHashSet<String>()
        val viewBox = numbers(root.getAttribute("viewBox")).takeIf { it.size == 4 }
        val declaredW = length(root.getAttribute("width"))
        val declaredH = length(root.getAttribute("height"))

        // The viewport is the SVG's own coordinate space, so path data needs no rescaling. Only the
        // viewBox's origin offset has to be applied, and that is exactly a `<group>` translate.
        val viewportW = viewBox?.get(2)?.takeIf { it > 0f } ?: declaredW ?: 24f
        val viewportH = viewBox?.get(3)?.takeIf { it > 0f } ?: declaredH ?: 24f
        val minX = viewBox?.get(0) ?: 0f
        val minY = viewBox?.get(1) ?: 0f

        val ctx = Ctx(gradients = collectPaintServers(root), clipPaths = collectClipPaths(root), warnings = warnings)
        val nodes = ArrayList<VectorNode>()
        appendChildren(root, ctx, Style.ROOT, Affine.IDENTITY, nodes, options, depth = 0)

        val body: List<VectorNode> =
            if (minX == 0f && minY == 0f) nodes
            else listOf(group(nodes, translateX = -minX, translateY = -minY))

        val spec = VectorSpec(
            widthDp = options.widthDp ?: declaredW ?: viewportW,
            heightDp = options.heightDp ?: declaredH ?: viewportH,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            rootAlpha = 1f,
            nodes = body,
        )
        if (spec.nodes.isEmpty()) warnings += "No drawable geometry found in the SVG"
        return SvgConversion(spec, warnings.toList())
    }

    /** [svgText] converted straight to XML text, or null when it isn't an SVG. */
    fun convertToXml(svgText: String, options: SvgConvertOptions = SvgConvertOptions()): String? =
        toSpec(svgText, options)?.let { VectorDrawableWriter.write(it.spec) }

    // --- tree walk -------------------------------------------------------------------------------------

    private class Ctx(
        /** `id` → the fallback flat colour for `fill="url(#id)"`, from the paint server's first stop. */
        val gradients: Map<String, Long>,
        /** `id` → the single `pathData` of a `<clipPath>`, when it has exactly one shape. */
        val clipPaths: Map<String, String>,
        val warnings: MutableSet<String>,
    )

    /**
     * Appends [el]'s children as vector nodes. [inherited] carries the SVG cascade; [pending] carries the
     * transform that still has to be applied to descendants because no `<group>` could express it. It is
     * only ever non-identity for a rotation/skew/matrix, which gets baked into the path coordinates.
     */
    private fun appendChildren(
        el: Element,
        ctx: Ctx,
        inherited: Style,
        pending: Affine,
        out: MutableList<VectorNode>,
        options: SvgConvertOptions,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        for (child in elements(el)) {
            val tag = child.tagName.substringAfterLast(':')
            val style = inherited.resolve(child)
            if (style.display == "none") continue
            when (tag) {
                "g", "a", "svg" -> appendContainer(child, ctx, style, pending, out, options, depth)
                "path" -> child.getAttribute("d").ifEmpty { null }
                    ?.let { emitPath(it, child, ctx, style, pending, out, options) }

                "rect" -> rectPath(child)?.let { emitPath(it, child, ctx, style, pending, out, options) }
                "circle" -> circlePath(child)?.let { emitPath(it, child, ctx, style, pending, out, options) }
                "ellipse" -> ellipsePath(child)?.let { emitPath(it, child, ctx, style, pending, out, options) }
                "line" -> linePath(child)?.let { emitPath(it, child, ctx, style, pending, out, options) }
                "polygon" -> polyPath(child, close = true)
                    ?.let { emitPath(it, child, ctx, style, pending, out, options) }

                "polyline" -> polyPath(child, close = false)
                    ?.let { emitPath(it, child, ctx, style, pending, out, options) }

                // Definitions were harvested up front; they draw nothing on their own.
                "defs", "clipPath", "linearGradient", "radialGradient", "symbol", "marker", "mask",
                "pattern", "filter", "style", "title", "desc", "metadata",
                -> Unit

                "text", "tspan" -> ctx.warnings += "Text is not supported by VectorDrawable: outline it in the editor first"
                "use" -> ctx.warnings += "<use> references are not supported: flatten the SVG before importing"
                "image" -> ctx.warnings += "Embedded <image> data is not supported: import it as a bitmap instead"
                else -> ctx.warnings += "Skipped unsupported element <$tag>"
            }
        }
    }

    /** A `<g>`: emits a real `<group>` when its transform is expressible, otherwise folds it into [pending]. */
    private fun appendContainer(
        el: Element,
        ctx: Ctx,
        style: Style,
        pending: Affine,
        out: MutableList<VectorNode>,
        options: SvgConvertOptions,
        depth: Int,
    ) {
        val combined = pending * Affine.parse(el.getAttribute("transform"))
        val clip = clipRefOf(el, ctx)
        if (combined.isAxisAligned) {
            val children = ArrayList<VectorNode>()
            appendChildren(el, ctx, style, Affine.IDENTITY, children, options, depth + 1)
            if (children.isEmpty()) return
            if (combined.isIdentity && clip == null) {
                out += children // nothing to express, so splice the children in and skip a pointless <group>
            } else {
                out += group(
                    children,
                    scaleX = combined.a, scaleY = combined.d,
                    translateX = combined.e, translateY = combined.f,
                    clipPathData = clip,
                )
            }
        } else {
            // A rotation/skew/matrix cannot nest as a <group>; descendants bake it into their coordinates.
            if (clip != null) ctx.warnings += "A clip path under a rotated or skewed group was dropped"
            appendChildren(el, ctx, style, combined, out, options, depth + 1)
        }
    }

    private fun emitPath(
        pathData: String,
        el: Element,
        ctx: Ctx,
        style: Style,
        pending: Affine,
        out: MutableList<VectorNode>,
        options: SvgConvertOptions,
    ) {
        val own = Affine.parse(el.getAttribute("transform"))
        val m = pending * own
        val clip = clipRefOf(el, ctx)

        val fill = paint(style.fill, style.fillOpacity * style.opacity, ctx, options)
        val stroke = paint(style.stroke, style.strokeOpacity * style.opacity, ctx, options)
        if (fill == null && stroke == null) return // `fill="none"` with no stroke draws nothing

        // A baked non-uniform scale can't scale a stroke width in both axes; use the geometric mean.
        val strokeScale = if (m.isIdentity) 1f else sqrt(abs(m.a * m.d - m.b * m.c)).takeIf { it > 0f } ?: 1f
        if (stroke != null && !m.isIdentity && abs(abs(m.a) - abs(m.d)) > 1e-3f) {
            ctx.warnings += "A stroke under a non-uniform scale was approximated"
        }

        val path = VectorPath(
            pathData = if (m.isAxisAligned) pathData else SvgPathData.transformPathData(pathData, m),
            fillColor = fill?.first,
            strokeColor = stroke?.first,
            strokeWidthVp = if (stroke == null) 0f else style.strokeWidth * strokeScale,
            fillAlpha = fill?.second ?: 1f,
            strokeAlpha = stroke?.second ?: 1f,
            fillRule = if (style.fillRule == "evenodd") FillRule.EVEN_ODD else FillRule.NON_ZERO,
            strokeCap = when (style.strokeCap) {
                "round" -> StrokeCap.ROUND
                "square" -> StrokeCap.SQUARE
                else -> StrokeCap.BUTT
            },
            strokeJoin = when (style.strokeJoin) {
                "round" -> StrokeJoin.ROUND
                "bevel" -> StrokeJoin.BEVEL
                else -> StrokeJoin.MITER
            },
            strokeMiter = style.strokeMiter,
        )

        // An axis-aligned transform on the shape itself still needs a wrapper to carry it.
        out += when {
            clip == null && m.isIdentity -> path
            m.isAxisAligned -> group(
                listOf(path),
                scaleX = m.a, scaleY = m.d, translateX = m.e, translateY = m.f,
                clipPathData = clip,
            )

            clip != null -> group(listOf(path), clipPathData = clip)
            else -> path
        }
    }

    /**
     * A `<group>` with its numbers normalised. Matrix arithmetic readily produces `-0.0`, which compares equal
     * to `0.0` but is a different value to `equals`, and would be written out as a pointless `"-0"`, so it is
     * folded back to positive zero here rather than at every call site.
     */
    private fun group(
        children: List<VectorNode>,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        translateX: Float = 0f,
        translateY: Float = 0f,
        clipPathData: String? = null,
    ) = VectorGroup(
        children = children,
        scaleX = z(scaleX), scaleY = z(scaleY),
        translateX = z(translateX), translateY = z(translateY),
        clipPathData = clipPathData,
    )

    private fun z(v: Float): Float = if (v == 0f) 0f else v

    // --- paint -----------------------------------------------------------------------------------------

    /**
     * A resolved paint as `(argb, alpha)`, or null for "don't draw". [raw] is a `fill`/`stroke` value; an
     * `url(#id)` paint server degrades to its first stop colour, since a `<path>` can only take a flat colour.
     */
    private fun paint(raw: String, alpha: Float, ctx: Ctx, options: SvgConvertOptions): Pair<Long, Float>? {
        val v = raw.trim()
        if (v.isEmpty() || v.equals("none", ignoreCase = true) || v.equals("transparent", ignoreCase = true)) return null
        val a = alpha.coerceIn(0f, 1f)
        if (a == 0f) return null
        options.overrideColor?.let { return it to a }

        if (v.startsWith("url(")) {
            val id = v.substringAfter('#').substringBefore(')').trim()
            val stop = ctx.gradients[id]
            ctx.warnings += if (stop != null) {
                "A gradient fill was flattened to its first colour: VectorDrawable paths take a flat colour"
            } else {
                "Unresolved paint reference url(#$id), painted grey"
            }
            return (stop ?: 0xFF888888L) to a
        }
        val color = SvgColor.parse(v) ?: run {
            ctx.warnings += "Unrecognised colour \"$v\", painted black"
            0xFF000000L
        }
        // An `#RRGGBBAA` / `rgba()` alpha multiplies into the path's own alpha.
        val ownAlpha = (((color shr 24) and 0xFF) / 255f)
        return (0xFF000000L or (color and 0xFFFFFFL)) to (a * ownAlpha).coerceIn(0f, 1f)
    }

    /** `id` → first stop colour, for every `<linearGradient>`/`<radialGradient>` anywhere in the document. */
    private fun collectPaintServers(root: Element): Map<String, Long> {
        val out = HashMap<String, Long>()
        forEachDescendant(root) { el ->
            val tag = el.tagName.substringAfterLast(':')
            if (tag != "linearGradient" && tag != "radialGradient") return@forEachDescendant
            val id = el.getAttribute("id").ifEmpty { return@forEachDescendant }
            val stop = elements(el).firstOrNull { it.tagName.substringAfterLast(':') == "stop" }
            val raw = stop?.let {
                it.getAttribute("stop-color").ifEmpty { null } ?: declarations(it.getAttribute("style"))["stop-color"]
            }
            SvgColor.parse(raw?.trim() ?: return@forEachDescendant)?.let { out[id] = it }
        }
        return out
    }

    /** `id` → path data, for each `<clipPath>` holding exactly one `<path>`/shape we can express. */
    private fun collectClipPaths(root: Element): Map<String, String> {
        val out = HashMap<String, String>()
        forEachDescendant(root) { el ->
            if (el.tagName.substringAfterLast(':') != "clipPath") return@forEachDescendant
            val id = el.getAttribute("id").ifEmpty { return@forEachDescendant }
            val shapes = elements(el).mapNotNull { shapePathData(it) }
            if (shapes.size == 1) out[id] = shapes[0]
        }
        return out
    }

    private fun shapePathData(el: Element): String? = when (el.tagName.substringAfterLast(':')) {
        "path" -> el.getAttribute("d").ifEmpty { null }
        "rect" -> rectPath(el)
        "circle" -> circlePath(el)
        "ellipse" -> ellipsePath(el)
        "polygon" -> polyPath(el, close = true)
        else -> null
    }

    /** The `clip-path="url(#id)"` outline for [el], when it names a `<clipPath>` we could express. */
    private fun clipRefOf(el: Element, ctx: Ctx): String? {
        val raw = el.getAttribute("clip-path").ifEmpty { null } ?: return null
        if (!raw.contains("url(")) return null
        val id = raw.substringAfter('#').substringBefore(')').trim()
        return ctx.clipPaths[id] ?: run {
            ctx.warnings += "Unsupported clip path url(#$id) was ignored"
            null
        }
    }

    // --- shape → pathData ------------------------------------------------------------------------------

    private fun rectPath(el: Element): String? {
        val w = f(el, "width") ?: return null
        val h = f(el, "height") ?: return null
        if (w <= 0f || h <= 0f) return null
        val x = f(el, "x") ?: 0f
        val y = f(el, "y") ?: 0f
        // SVG: a missing rx mirrors ry (and vice versa); each is clamped to half the side.
        val rxRaw = f(el, "rx")
        val ryRaw = f(el, "ry")
        val rx = (rxRaw ?: ryRaw ?: 0f).coerceIn(0f, w / 2f)
        val ry = (ryRaw ?: rxRaw ?: 0f).coerceIn(0f, h / 2f)
        if (rx <= 0f || ry <= 0f) {
            return "M${n(x)},${n(y)}h${n(w)}v${n(h)}h${n(-w)}Z"
        }
        return buildString {
            append("M").append(n(x + rx)).append(',').append(n(y))
            append("h").append(n(w - 2 * rx))
            append("a").append(n(rx)).append(',').append(n(ry)).append(" 0 0 1 ").append(n(rx)).append(',').append(n(ry))
            append("v").append(n(h - 2 * ry))
            append("a").append(n(rx)).append(',').append(n(ry)).append(" 0 0 1 ").append(n(-rx)).append(',').append(n(ry))
            append("h").append(n(-(w - 2 * rx)))
            append("a").append(n(rx)).append(',').append(n(ry)).append(" 0 0 1 ").append(n(-rx)).append(',').append(n(-ry))
            append("v").append(n(-(h - 2 * ry)))
            append("a").append(n(rx)).append(',').append(n(ry)).append(" 0 0 1 ").append(n(rx)).append(',').append(n(-ry))
            append("Z")
        }
    }

    private fun circlePath(el: Element): String? {
        val r = f(el, "r") ?: return null
        if (r <= 0f) return null
        return ovalPath(f(el, "cx") ?: 0f, f(el, "cy") ?: 0f, r, r)
    }

    private fun ellipsePath(el: Element): String? {
        val rx = f(el, "rx") ?: return null
        val ry = f(el, "ry") ?: return null
        if (rx <= 0f || ry <= 0f) return null
        return ovalPath(f(el, "cx") ?: 0f, f(el, "cy") ?: 0f, rx, ry)
    }

    /** A full ellipse as two half-sweep arcs, because one arc cannot close on itself. */
    private fun ovalPath(cx: Float, cy: Float, rx: Float, ry: Float): String =
        "M${n(cx - rx)},${n(cy)}" +
            "a${n(rx)},${n(ry)} 0 1 0 ${n(2 * rx)},0" +
            "a${n(rx)},${n(ry)} 0 1 0 ${n(-2 * rx)},0Z"

    private fun linePath(el: Element): String? {
        val x1 = f(el, "x1") ?: 0f
        val y1 = f(el, "y1") ?: 0f
        val x2 = f(el, "x2") ?: 0f
        val y2 = f(el, "y2") ?: 0f
        if (x1 == x2 && y1 == y2) return null
        return "M${n(x1)},${n(y1)}L${n(x2)},${n(y2)}"
    }

    private fun polyPath(el: Element, close: Boolean): String? {
        val pts = numbers(el.getAttribute("points"))
        if (pts.size < 4) return null
        val sb = StringBuilder("M").append(n(pts[0])).append(',').append(n(pts[1]))
        var i = 2
        while (i + 1 < pts.size) {
            sb.append('L').append(n(pts[i])).append(',').append(n(pts[i + 1]))
            i += 2
        }
        if (close) sb.append('Z')
        return sb.toString()
    }

    // --- style cascade ---------------------------------------------------------------------------------

    /**
     * The SVG properties that survive into a VectorDrawable, resolved down the tree. Inline `style="…"`
     * declarations beat presentation attributes, which beat the inherited value: the CSS cascade, minus
     * stylesheets and selectors (which VectorDrawable has no equivalent for anyway).
     */
    private data class Style(
        val fill: String,
        val stroke: String,
        val fillOpacity: Float,
        val strokeOpacity: Float,
        val opacity: Float,
        val strokeWidth: Float,
        val fillRule: String,
        val strokeCap: String,
        val strokeJoin: String,
        val strokeMiter: Float,
        val display: String,
    ) {

        fun resolve(el: Element): Style {
            val inline = declarations(el.getAttribute("style"))
            fun prop(name: String): String? =
                inline[name] ?: el.getAttribute(name).trim().ifEmpty { null }

            // `opacity` is a group property, so it multiplies down rather than being inherited as-is.
            val ownOpacity = prop("opacity")?.let(::opacityValue) ?: 1f
            return Style(
                fill = prop("fill") ?: fill,
                stroke = prop("stroke") ?: stroke,
                fillOpacity = prop("fill-opacity")?.let(::opacityValue) ?: fillOpacity,
                strokeOpacity = prop("stroke-opacity")?.let(::opacityValue) ?: strokeOpacity,
                opacity = opacity * ownOpacity,
                strokeWidth = prop("stroke-width")?.let { length(it) } ?: strokeWidth,
                fillRule = prop("fill-rule")?.lowercase() ?: fillRule,
                strokeCap = prop("stroke-linecap")?.lowercase() ?: strokeCap,
                strokeJoin = prop("stroke-linejoin")?.lowercase() ?: strokeJoin,
                strokeMiter = prop("stroke-miterlimit")?.toFloatOrNull() ?: strokeMiter,
                display = prop("display")?.lowercase() ?: "",
            )
        }

        companion object {
            /** SVG's initial values: black fill, no stroke, fully opaque. */
            val ROOT = Style(
                fill = "black", stroke = "none",
                fillOpacity = 1f, strokeOpacity = 1f, opacity = 1f,
                strokeWidth = 1f, fillRule = "nonzero",
                strokeCap = "butt", strokeJoin = "miter", strokeMiter = 4f,
                display = "",
            )
        }
    }

    /** `fill:#fff;stroke:none` → a property map. */
    private fun declarations(style: String): Map<String, String> {
        if (style.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (part in style.split(';')) {
            val k = part.substringBefore(':', "").trim().lowercase()
            val v = part.substringAfter(':', "").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
        return out
    }

    /** An opacity value: a plain `0..1` number, or a percentage. */
    private fun opacityValue(raw: String): Float {
        val s = raw.trim()
        val pct = s.endsWith("%")
        val v = (if (pct) s.dropLast(1) else s).toFloatOrNull() ?: return 1f
        return (if (pct) v / 100f else v).coerceIn(0f, 1f)
    }

    // --- value helpers ---------------------------------------------------------------------------------

    private fun f(el: Element, name: String): Float? = length(el.getAttribute(name))

    /**
     * An SVG length in user units. Absolute CSS units are converted at the spec's 96dpi (`1in` = 96px);
     * percentages and font-relative units have no fixed value here, so they're dropped.
     */
    private fun length(raw: String?): Float? {
        val s = raw?.trim()?.ifEmpty { null } ?: return null
        if (s.endsWith("%")) return null
        val text = Affine.NUMBER.matchAt(s, 0)?.value ?: return null
        val num = text.toFloatOrNull() ?: return null
        return when (s.removePrefix(text).trim().lowercase()) {
            "", "px" -> num
            "pt" -> num * 96f / 72f
            "pc" -> num * 16f
            "in" -> num * 96f
            "cm" -> num * 96f / 2.54f
            "mm" -> num * 96f / 25.4f
            "q" -> num * 96f / 101.6f
            else -> num // em/ex/ch/rem/vw: no resolvable basis, so take the number as user units
        }
    }

    private fun numbers(raw: String): List<Float> = Affine.numbers(raw)

    private fun n(v: Float): String = SvgPathData.n(v)

    private fun elements(el: Element): List<Element> {
        val kids = el.childNodes
        val out = ArrayList<Element>(kids.length)
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) out += node as Element
        }
        return out
    }

    private fun forEachDescendant(el: Element, action: (Element) -> Unit) {
        for (child in elements(el)) {
            action(child)
            forEachDescendant(child, action)
        }
    }

    private fun builder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        isExpandEntityReferences = false
    }.newDocumentBuilder()
}

/**
 * CSS/SVG colour parsing to `0xAARRGGBB`, covering the forms that appear in real icon files: hex (3/4/6/8 digit),
 * `rgb()`/`rgba()` with numbers or percentages, `currentColor` (black, since a drawable has no cascade to
 * inherit from), and the named colours. Android's own `#…` forms are delegated to [AndroidColor].
 */
internal object SvgColor {

    fun parse(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.equals("currentColor", ignoreCase = true)) return 0xFF000000L
        if (s.startsWith("#")) return parseHex(s)
        if (s.startsWith("rgb", ignoreCase = true)) return parseRgb(s)
        return NAMED[s.lowercase()]
    }

    /** Hex colours, including CSS's `#RRGGBBAA` (which is byte-swapped from Android's `#AARRGGBB`). */
    private fun parseHex(s: String): Long? {
        val h = s.substring(1)
        return when (h.length) {
            8 -> {
                val v = h.toLongOrNull(16) ?: return null
                val a = v and 0xFF
                ((a shl 24) or (v ushr 8)) // #RRGGBBAA → 0xAARRGGBB
            }

            4 -> {
                val v = h.map { hexDigit(it).takeIf { d -> d >= 0 } ?: return null }
                val a = v[3] * 17L
                (a shl 24) or (v[0] * 17L shl 16) or (v[1] * 17L shl 8) or (v[2] * 17L)
            }

            else -> AndroidColor.parseHex(s)
        }
    }

    private fun parseRgb(s: String): Long? {
        val args = s.substringAfter('(', "").substringBefore(')').split(',', ' ', '/')
            .mapNotNull { it.trim().ifEmpty { null } }
        if (args.size < 3) return null
        val ch = args.take(3).map { component(it) ?: return null }
        val a = args.getOrNull(3)?.let { alphaComponent(it) } ?: 255
        return (a.toLong() shl 24) or (ch[0].toLong() shl 16) or (ch[1].toLong() shl 8) or ch[2].toLong()
    }

    private fun component(raw: String): Int? {
        val pct = raw.endsWith("%")
        val v = (if (pct) raw.dropLast(1) else raw).toFloatOrNull() ?: return null
        return (if (pct) v * 255f / 100f else v).toInt().coerceIn(0, 255)
    }

    private fun alphaComponent(raw: String): Int {
        val pct = raw.endsWith("%")
        val v = (if (pct) raw.dropLast(1) else raw).toFloatOrNull() ?: return 255
        return ((if (pct) v / 100f else v) * 255f).toInt().coerceIn(0, 255)
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private val NAMED: Map<String, Long> = mapOf(
        "aqua" to 0xFF00FFFFL, "aquamarine" to 0xFF7FFFD4L, "beige" to 0xFFF5F5DCL, "black" to 0xFF000000L,
        "blue" to 0xFF0000FFL, "brown" to 0xFFA52A2AL, "chocolate" to 0xFFD2691EL, "coral" to 0xFFFF7F50L,
        "crimson" to 0xFFDC143CL, "cyan" to 0xFF00FFFFL, "darkblue" to 0xFF00008BL, "darkcyan" to 0xFF008B8BL,
        "darkgray" to 0xFFA9A9A9L, "darkgrey" to 0xFFA9A9A9L, "darkgreen" to 0xFF006400L,
        "darkorange" to 0xFFFF8C00L, "darkred" to 0xFF8B0000L, "darkslategray" to 0xFF2F4F4FL,
        "deeppink" to 0xFFFF1493L, "dimgray" to 0xFF696969L, "dodgerblue" to 0xFF1E90FFL,
        "fuchsia" to 0xFFFF00FFL, "gold" to 0xFFFFD700L, "gray" to 0xFF808080L, "grey" to 0xFF808080L,
        "green" to 0xFF008000L, "greenyellow" to 0xFFADFF2FL, "hotpink" to 0xFFFF69B4L,
        "indigo" to 0xFF4B0082L, "ivory" to 0xFFFFFFF0L, "khaki" to 0xFFF0E68CL, "lavender" to 0xFFE6E6FAL,
        "lightblue" to 0xFFADD8E6L, "lightgray" to 0xFFD3D3D3L, "lightgrey" to 0xFFD3D3D3L,
        "lightgreen" to 0xFF90EE90L, "lime" to 0xFF00FF00L, "limegreen" to 0xFF32CD32L,
        "magenta" to 0xFFFF00FFL, "maroon" to 0xFF800000L, "midnightblue" to 0xFF191970L,
        "navy" to 0xFF000080L, "olive" to 0xFF808000L, "orange" to 0xFFFFA500L, "orangered" to 0xFFFF4500L,
        "orchid" to 0xFFDA70D6L, "pink" to 0xFFFFC0CBL, "purple" to 0xFF800080L, "red" to 0xFFFF0000L,
        "royalblue" to 0xFF4169E1L, "salmon" to 0xFFFA8072L, "seagreen" to 0xFF2E8B57L,
        "sienna" to 0xFFA0522DL, "silver" to 0xFFC0C0C0L, "skyblue" to 0xFF87CEEBL,
        "slateblue" to 0xFF6A5ACDL, "slategray" to 0xFF708090L, "steelblue" to 0xFF4682B4L,
        "tan" to 0xFFD2B48CL, "teal" to 0xFF008080L, "tomato" to 0xFFFF6347L, "turquoise" to 0xFF40E0D0L,
        "violet" to 0xFFEE82EEL, "wheat" to 0xFFF5DEB3L, "white" to 0xFFFFFFFFL, "whitesmoke" to 0xFFF5F5F5L,
        "yellow" to 0xFFFFFF00L, "yellowgreen" to 0xFF9ACD32L,
    )
}
