package dev.ide.android.support.preview

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a `res/drawable` (or `res/color`) XML document into a render-ready [DrawablePreview], resolving
 * every `@color`/`@dimen`/`@drawable` reference through the supplied [DrawableResolver]. Pure `java.nio` +
 * JAXP (namespace-unaware, attributes read by qualified name), so it runs identically on desktop and ART
 * and is fully unit-testable with [DrawableResolver.NONE].
 *
 * Coverage: `<shape>` (rectangle/oval/line/ring · solid/gradient/stroke/corners/size), `<vector>` (viewport
 * + `<path>`/`<group>`), `<selector>`, `<layer-list>`, `<color>`, `<ripple>`, `<inset>`, `<clip>`/`<scale>`/
 * `<rotate>` (unwrap), and `<bitmap>`/`<nine-patch>` / `@drawable` refs to image files. Unknown roots become
 * [DrawablePreview.Unsupported] rather than throwing.
 */
object DrawablePreviewParser {

    private const val MAX_DEPTH = 12

    /** Parse [text]; returns [DrawablePreview.Unsupported] on malformed XML rather than throwing. */
    fun parse(text: String, resolver: DrawableResolver = DrawableResolver.NONE): DrawablePreview {
        val root = runCatching {
            builder().parse(text.byteInputStream(Charsets.UTF_8)).documentElement
        }.getOrNull() ?: return DrawablePreview.Unsupported("?", "Malformed XML")
        return parseElement(root, resolver, 0)
    }

    private fun parseElement(el: Element, r: DrawableResolver, depth: Int): DrawablePreview {
        if (depth > MAX_DEPTH) return DrawablePreview.Unsupported(el.tagName, "Reference cycle")
        return when (el.tagName.substringAfterLast(':')) {
            "shape", "GradientDrawable" -> parseShape(el, r)
            "vector" -> parseVector(el, r)
            "selector" -> parseSelector(el, r, depth)
            "layer-list" -> DrawablePreview.Layers(parseLayers(el, r, depth))
            "color" -> (colorToken(androidAttr(el, "color"), r))?.let { DrawablePreview.SolidColor(it) }
                ?: DrawablePreview.Unsupported("color", "Unresolved color")
            "ripple" -> parseRipple(el, r, depth)
            "inset" -> parseInset(el, r, depth)
            "clip", "scale", "rotate" -> unwrap(el, r, depth)
            "bitmap", "nine-patch", "animated-image" -> bitmapRef(androidAttr(el, "src"), r)
                ?: DrawablePreview.Unsupported(el.tagName, "No image source")
            "level-list", "transition" -> firstItemOr(el, r, depth, el.tagName)
            "animated-vector" -> androidAttr(el, "drawable")?.let { resolveDrawableRef(it, r, depth) }
                ?: DrawablePreview.Unsupported("animated-vector", "Animated vector")
            else -> DrawablePreview.Unsupported(el.tagName, "Unsupported drawable")
        }
    }

    // --- shape -----------------------------------------------------------------------------------------

    private fun parseShape(el: Element, r: DrawableResolver): DrawablePreview {
        val kind = when (androidAttr(el, "shape")?.lowercase()) {
            "oval" -> ShapeKind.OVAL
            "line" -> ShapeKind.LINE
            "ring" -> ShapeKind.RING
            else -> ShapeKind.RECTANGLE
        }
        var solid: Long? = null
        var gradient: GradientSpec? = null
        var strokeColor: Long? = null
        var strokeWidth = 0f
        var dashWidth = 0f
        var dashGap = 0f
        var tl = 0f; var tr = 0f; var br = 0f; var bl = 0f
        var sizeW = 0f; var sizeH = 0f

        for (child in elements(el)) {
            when (child.tagName.substringAfterLast(':')) {
                "solid" -> solid = colorToken(androidAttr(child, "color"), r)
                "gradient" -> gradient = parseGradient(child, r)
                "stroke" -> {
                    strokeColor = colorToken(androidAttr(child, "color"), r)
                    strokeWidth = dp(androidAttr(child, "width"), r)
                    dashWidth = dp(androidAttr(child, "dashWidth"), r)
                    dashGap = dp(androidAttr(child, "dashGap"), r)
                }
                "corners" -> {
                    val radius = dp(androidAttr(child, "radius"), r)
                    tl = dp(androidAttr(child, "topLeftRadius"), r).ifZero(radius)
                    tr = dp(androidAttr(child, "topRightRadius"), r).ifZero(radius)
                    br = dp(androidAttr(child, "bottomRightRadius"), r).ifZero(radius)
                    bl = dp(androidAttr(child, "bottomLeftRadius"), r).ifZero(radius)
                }
                "size" -> {
                    sizeW = dp(androidAttr(child, "width"), r)
                    sizeH = dp(androidAttr(child, "height"), r)
                }
            }
        }
        val innerRatio = androidAttr(el, "innerRadiusRatio")?.toFloatOrNull() ?: 9f
        val thicknessRatio = androidAttr(el, "thicknessRatio")?.toFloatOrNull() ?: 3f
        return DrawablePreview.Shape(
            ShapeSpec(
                shape = kind, solidColor = solid, gradient = gradient,
                strokeColor = strokeColor, strokeWidthDp = strokeWidth, dashWidthDp = dashWidth, dashGapDp = dashGap,
                cornerTopLeftDp = tl, cornerTopRightDp = tr, cornerBottomRightDp = br, cornerBottomLeftDp = bl,
                intrinsicWidthDp = sizeW, intrinsicHeightDp = sizeH,
                innerRadiusFraction = (1f / innerRatio).coerceIn(0.05f, 0.9f),
                thicknessFraction = (1f / thicknessRatio).coerceIn(0.02f, 0.45f),
            ),
        )
    }

    private fun parseGradient(el: Element, r: DrawableResolver): GradientSpec {
        val kind = when (androidAttr(el, "type")?.lowercase()) {
            "radial" -> GradientKind.RADIAL
            "sweep" -> GradientKind.SWEEP
            else -> GradientKind.LINEAR
        }
        val start = colorToken(androidAttr(el, "startColor"), r) ?: 0xFF888888L
        val end = colorToken(androidAttr(el, "endColor"), r) ?: 0xFF222222L
        val center = colorToken(androidAttr(el, "centerColor"), r)
        val radiusRaw = androidAttr(el, "gradientRadius")
        val radiusFraction = when {
            radiusRaw == null -> 0.5f
            radiusRaw.endsWith("%p") -> (radiusRaw.removeSuffix("%p").toFloatOrNull() ?: 50f) / 100f
            radiusRaw.endsWith("%") -> (radiusRaw.removeSuffix("%").toFloatOrNull() ?: 50f) / 100f
            else -> 0.5f
        }
        return GradientSpec(
            kind = kind, startColor = start, centerColor = center, endColor = end,
            angle = androidAttr(el, "angle")?.toFloatOrNull()?.toInt() ?: 0,
            centerX = androidAttr(el, "centerX")?.toFloatOrNull() ?: 0.5f,
            centerY = androidAttr(el, "centerY")?.toFloatOrNull() ?: 0.5f,
            radiusFraction = radiusFraction.coerceIn(0.05f, 1.5f),
        )
    }

    // --- vector ----------------------------------------------------------------------------------------

    private fun parseVector(el: Element, r: DrawableResolver): DrawablePreview {
        val paths = ArrayList<VectorPath>()
        collectPaths(el, r, paths)
        return DrawablePreview.Vector(
            VectorSpec(
                widthDp = dp(androidAttr(el, "width"), r).ifZero(24f),
                heightDp = dp(androidAttr(el, "height"), r).ifZero(24f),
                viewportWidth = androidAttr(el, "viewportWidth")?.toFloatOrNull()?.takeIf { it > 0 } ?: 24f,
                viewportHeight = androidAttr(el, "viewportHeight")?.toFloatOrNull()?.takeIf { it > 0 } ?: 24f,
                rootAlpha = androidAttr(el, "alpha")?.toFloatOrNull() ?: 1f,
                paths = paths,
            ),
        )
    }

    /** Recursively gather `<path>` elements (group transforms ignored — fine for a static preview). */
    private fun collectPaths(el: Element, r: DrawableResolver, out: MutableList<VectorPath>) {
        for (child in elements(el)) {
            when (child.tagName.substringAfterLast(':')) {
                "path" -> {
                    val data = androidAttr(child, "pathData") ?: continue
                    out += VectorPath(
                        pathData = data,
                        fillColor = colorToken(androidAttr(child, "fillColor"), r),
                        strokeColor = colorToken(androidAttr(child, "strokeColor"), r),
                        strokeWidthVp = androidAttr(child, "strokeWidth")?.toFloatOrNull() ?: 0f,
                        fillAlpha = androidAttr(child, "fillAlpha")?.toFloatOrNull() ?: 1f,
                        strokeAlpha = androidAttr(child, "strokeAlpha")?.toFloatOrNull() ?: 1f,
                    )
                }
                "group" -> collectPaths(child, r, out)
            }
        }
    }

    // --- selector / layer-list / composites ------------------------------------------------------------

    private fun parseSelector(el: Element, r: DrawableResolver, depth: Int): DrawablePreview {
        val states = ArrayList<StateLayer>()
        for (item in elements(el)) {
            if (item.tagName.substringAfterLast(':') != "item") continue
            val drawable = itemDrawable(item, r, depth) ?: continue
            val flags = item.attributes.let { a ->
                (0 until a.length).mapNotNull { i ->
                    val attr = a.item(i)
                    val local = attr.nodeName.substringAfterLast(':')
                    if (local.startsWith("state_") && attr.nodeValue == "true") local else null
                }
            }
            states += StateLayer(flags, drawable)
        }
        // The static preview shows the "default" item — the one with no state flags, else the last.
        val default = states.firstOrNull { it.states.isEmpty() }?.drawable ?: states.lastOrNull()?.drawable
        return DrawablePreview.States(states, default)
    }

    private fun parseLayers(el: Element, r: DrawableResolver, depth: Int): List<Layer> {
        val out = ArrayList<Layer>()
        for (item in elements(el)) {
            if (item.tagName.substringAfterLast(':') != "item") continue
            val drawable = itemDrawable(item, r, depth) ?: continue
            out += Layer(
                drawable = drawable,
                insetLeftDp = dp(androidAttr(item, "left"), r),
                insetTopDp = dp(androidAttr(item, "top"), r),
                insetRightDp = dp(androidAttr(item, "right"), r),
                insetBottomDp = dp(androidAttr(item, "bottom"), r),
            )
        }
        return out
    }

    private fun parseRipple(el: Element, r: DrawableResolver, depth: Int): DrawablePreview {
        val layers = parseLayers(el, r, depth)
        if (layers.isNotEmpty()) return DrawablePreview.Layers(layers)
        // A bare <ripple android:color> with no content — show its color.
        return colorToken(androidAttr(el, "color"), r)?.let { DrawablePreview.SolidColor(it) }
            ?: DrawablePreview.Unsupported("ripple", "Empty ripple")
    }

    private fun parseInset(el: Element, r: DrawableResolver, depth: Int): DrawablePreview {
        val inner = itemDrawable(el, r, depth) ?: return DrawablePreview.Unsupported("inset", "No drawable")
        val all = dp(androidAttr(el, "inset"), r)
        return DrawablePreview.Layers(
            listOf(
                Layer(
                    inner,
                    insetLeftDp = dp(androidAttr(el, "insetLeft"), r).ifZero(all),
                    insetTopDp = dp(androidAttr(el, "insetTop"), r).ifZero(all),
                    insetRightDp = dp(androidAttr(el, "insetRight"), r).ifZero(all),
                    insetBottomDp = dp(androidAttr(el, "insetBottom"), r).ifZero(all),
                ),
            ),
        )
    }

    private fun unwrap(el: Element, r: DrawableResolver, depth: Int): DrawablePreview =
        itemDrawable(el, r, depth) ?: DrawablePreview.Unsupported(el.tagName, "No drawable")

    private fun firstItemOr(el: Element, r: DrawableResolver, depth: Int, tag: String): DrawablePreview {
        for (item in elements(el)) {
            if (item.tagName.substringAfterLast(':') != "item") continue
            itemDrawable(item, r, depth)?.let { return it }
        }
        return DrawablePreview.Unsupported(tag, "No drawable")
    }

    /**
     * The drawable an `<item>` / wrapper element points at: an inline child element, an `android:drawable`
     * reference, or an `android:color` (color state list / tinted item).
     */
    private fun itemDrawable(el: Element, r: DrawableResolver, depth: Int): DrawablePreview? {
        elements(el).firstOrNull()?.let { return parseElement(it, r, depth + 1) }
        androidAttr(el, "drawable")?.let { return resolveDrawableRef(it, r, depth) }
        androidAttr(el, "color")?.let { raw -> colorToken(raw, r)?.let { return DrawablePreview.SolidColor(it) } }
        return null
    }

    private fun resolveDrawableRef(ref: String, r: DrawableResolver, depth: Int): DrawablePreview {
        if (depth > MAX_DEPTH) return DrawablePreview.Unsupported("?", "Reference cycle")
        val t = ref.trim()
        if (t.startsWith("#")) return AndroidColor.parseHex(t)?.let { DrawablePreview.SolidColor(it) }
            ?: DrawablePreview.Unsupported("color", "Bad color")
        if (typeOf(t) == "color") return colorToken(t, r)?.let { DrawablePreview.SolidColor(it) }
            ?: DrawablePreview.Unsupported("color", "Unresolved color")
        return when (val rd = r.resolveDrawable(t)) {
            is ResolvedDrawable.Xml -> parse(rd.text, r)
            is ResolvedDrawable.BitmapFile -> DrawablePreview.BitmapRef(rd.resType, rd.resName, rd.path)
            null -> DrawablePreview.Unsupported("drawable", "Unresolved $t")
        }
    }

    // --- value helpers ---------------------------------------------------------------------------------

    /** Resolve a color attribute value (literal `#…`, `@color/…`, `@android:color/…`) to ARGB, or null. */
    private fun colorToken(raw: String?, r: DrawableResolver): Long? {
        val s = raw?.trim()?.ifEmpty { null } ?: return null
        if (s.startsWith("#")) return AndroidColor.parseHex(s)
        if (s.startsWith("?")) return null                       // theme attr — unknown statically
        if (s.startsWith("@")) {
            val name = s.substringAfterLast('/')
            if (s.contains("android:")) return AndroidColor.framework(name)
            return r.resolveColor(s)
        }
        return null
    }

    /** A dp value from a dimension literal (`12dp`/`8dip`/`2px`) or `@dimen/…`, else 0. */
    private fun dp(raw: String?, r: DrawableResolver): Float {
        val s = raw?.trim()?.ifEmpty { null } ?: return 0f
        if (s.startsWith("@")) return r.resolveDimenDp(s) ?: 0f
        val num = DIMEN.find(s)?.groupValues?.get(1)?.toFloatOrNull() ?: return 0f
        return num // dp/dip/px/sp all rendered at the same logical scale in the preview
    }

    private val DIMEN = Regex("""^(-?\d+(?:\.\d+)?)""")

    /** The resource type token of `@type/name` / `@pkg:type/name`, or null. */
    private fun typeOf(ref: String): String? =
        Regex("""@\+?(?:[A-Za-z][\w.]*:)?([A-Za-z]\w*)/""").find(ref)?.groupValues?.get(1)

    private fun bitmapRef(ref: String?, r: DrawableResolver): DrawablePreview? {
        val s = ref?.trim()?.ifEmpty { null } ?: return null
        val name = s.substringAfterLast('/')
        val type = typeOf(s) ?: "drawable"
        val path = (r.resolveDrawable(s) as? ResolvedDrawable.BitmapFile)?.path
        return DrawablePreview.BitmapRef(type, name, path)
    }

    // --- DOM helpers -----------------------------------------------------------------------------------

    private fun elements(el: Element): List<Element> {
        val kids = el.childNodes
        val out = ArrayList<Element>(kids.length)
        for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { if (it.nodeType == Node.ELEMENT_NODE) out += it }
        return out
    }

    /** Read an attribute by its local name, trying the `android:` prefix first then the bare name. */
    private fun androidAttr(el: Element, local: String): String? {
        el.getAttribute("android:$local").ifEmpty { null }?.let { return it }
        return el.getAttribute(local).ifEmpty { null }
    }

    private fun Float.ifZero(other: Float): Float = if (this == 0f) other else this

    private fun builder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        isExpandEntityReferences = false
    }.newDocumentBuilder()
}
