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
 * + a `<path>`/`<group>` tree with transforms, `<clip-path>` and fill rules), `<selector>`, `<layer-list>`,
 * `<color>`, `<ripple>`, `<inset>`, `<clip>`/`<scale>`/
 * `<rotate>` (unwrap), `<bitmap>`/`<nine-patch>` / `@drawable` refs to image files, and the animated forms:
 * `<animation-list>` frames and `<animated-vector>` targets with their property animators, from `res/animator`
 * or inlined through `<aapt:attr>`. Unknown roots become [DrawablePreview.Unsupported] rather than throwing.
 */
object DrawablePreviewParser {

    private const val MAX_DEPTH = 12

    /** An animator's duration when it declares none (`ValueAnimator`'s default). */
    private const val DEFAULT_DURATION_MS = 300L

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
            "adaptive-icon" -> parseAdaptiveIcon(el, r, depth)
            "color" -> (colorToken(androidAttr(el, "color"), r))?.let { DrawablePreview.SolidColor(it) }
                ?: DrawablePreview.Unsupported("color", "Unresolved color")
            "ripple" -> parseRipple(el, r, depth)
            "inset" -> parseInset(el, r, depth)
            "clip", "scale", "rotate" -> unwrap(el, r, depth)
            "bitmap", "nine-patch", "animated-image" -> bitmapRef(androidAttr(el, "src"), r)
                ?: DrawablePreview.Unsupported(el.tagName, "No image source")
            "level-list", "transition" -> firstItemOr(el, r, depth, el.tagName)
            "animation-list" -> parseAnimationList(el, r, depth)
            "animated-vector" -> parseAnimatedVector(el, r, depth)
            // Its <transition>s only play between states; the states themselves read like a <selector>'s.
            "animated-selector" -> parseSelector(el, r, depth)
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
        val children = parseVectorNodes(el, r, 0)
        // A `<clip-path>` directly under `<vector>` clips the whole drawable; model it as an outer group.
        val rootClip = clipPathOf(el, r)
        return DrawablePreview.Vector(
            VectorSpec(
                widthDp = dp(androidAttr(el, "width"), r).ifZero(24f),
                heightDp = dp(androidAttr(el, "height"), r).ifZero(24f),
                viewportWidth = androidAttr(el, "viewportWidth")?.toFloatOrNull()?.takeIf { it > 0 } ?: 24f,
                viewportHeight = androidAttr(el, "viewportHeight")?.toFloatOrNull()?.takeIf { it > 0 } ?: 24f,
                rootAlpha = androidAttr(el, "alpha")?.toFloatOrNull() ?: 1f,
                nodes = if (rootClip == null) children
                else listOf(VectorGroup(children = children, clipPathData = rootClip)),
                name = androidAttr(el, "name"),
            ),
        )
    }

    /** The `<path>`/`<group>` children of a `<vector>` or `<group>`, in draw order, keeping the nesting. */
    private fun parseVectorNodes(el: Element, r: DrawableResolver, depth: Int): List<VectorNode> {
        if (depth > MAX_DEPTH) return emptyList()
        val out = ArrayList<VectorNode>()
        for (child in elements(el)) {
            when (child.tagName.substringAfterLast(':')) {
                "path" -> parseVectorPath(child, r)?.let { out += it }
                "group" -> out += parseVectorGroup(child, r, depth + 1)
            }
        }
        return out
    }

    private fun parseVectorPath(el: Element, r: DrawableResolver): VectorPath? {
        val data = pathData(androidAttr(el, "pathData"), r) ?: return null
        return VectorPath(
            pathData = data,
            fillColor = colorToken(androidAttr(el, "fillColor"), r),
            strokeColor = colorToken(androidAttr(el, "strokeColor"), r),
            strokeWidthVp = androidAttr(el, "strokeWidth")?.toFloatOrNull() ?: 0f,
            fillAlpha = androidAttr(el, "fillAlpha")?.toFloatOrNull() ?: 1f,
            strokeAlpha = androidAttr(el, "strokeAlpha")?.toFloatOrNull() ?: 1f,
            fillRule = if (androidAttr(el, "fillType").equals("evenOdd", ignoreCase = true))
                FillRule.EVEN_ODD else FillRule.NON_ZERO,
            strokeCap = when (androidAttr(el, "strokeLineCap")?.lowercase()) {
                "round" -> StrokeCap.ROUND
                "square" -> StrokeCap.SQUARE
                else -> StrokeCap.BUTT
            },
            strokeJoin = when (androidAttr(el, "strokeLineJoin")?.lowercase()) {
                "round" -> StrokeJoin.ROUND
                "bevel" -> StrokeJoin.BEVEL
                else -> StrokeJoin.MITER
            },
            strokeMiter = androidAttr(el, "strokeMiterLimit")?.toFloatOrNull() ?: 4f,
            name = androidAttr(el, "name"),
            trimPathStart = androidAttr(el, "trimPathStart")?.toFloatOrNull() ?: 0f,
            trimPathEnd = androidAttr(el, "trimPathEnd")?.toFloatOrNull() ?: 1f,
            trimPathOffset = androidAttr(el, "trimPathOffset")?.toFloatOrNull() ?: 0f,
        )
    }

    private fun parseVectorGroup(el: Element, r: DrawableResolver, depth: Int): VectorGroup = VectorGroup(
        children = parseVectorNodes(el, r, depth),
        translateX = androidAttr(el, "translateX")?.toFloatOrNull() ?: 0f,
        translateY = androidAttr(el, "translateY")?.toFloatOrNull() ?: 0f,
        scaleX = androidAttr(el, "scaleX")?.toFloatOrNull() ?: 1f,
        scaleY = androidAttr(el, "scaleY")?.toFloatOrNull() ?: 1f,
        rotation = androidAttr(el, "rotation")?.toFloatOrNull() ?: 0f,
        pivotX = androidAttr(el, "pivotX")?.toFloatOrNull() ?: 0f,
        pivotY = androidAttr(el, "pivotY")?.toFloatOrNull() ?: 0f,
        clipPathData = clipPathOf(el, r),
        name = androidAttr(el, "name"),
    )

    /** The `<clip-path android:pathData>` declared directly under [el], or null. */
    private fun clipPathOf(el: Element, r: DrawableResolver): String? = elements(el)
        .firstOrNull { it.tagName.substringAfterLast(':') == "clip-path" }
        ?.let { pathData(androidAttr(it, "pathData"), r) }

    /** Path data written inline, or kept in a `@string` resource as animated vectors usually do. */
    private fun pathData(raw: String?, r: DrawableResolver): String? {
        val s = raw?.trim()?.ifEmpty { null } ?: return null
        return if (s.startsWith("@")) r.resolveValue(s)?.trim()?.ifEmpty { null } else s
    }

    // --- animated drawables ----------------------------------------------------------------------------

    private fun parseAnimationList(el: Element, r: DrawableResolver, depth: Int): DrawablePreview {
        val frames = ArrayList<Frame>()
        for (item in elements(el)) {
            if (item.tagName.substringAfterLast(':') != "item") continue
            val drawable = itemDrawable(item, r, depth) ?: continue
            frames += Frame(drawable, androidAttr(item, "duration")?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
        }
        if (frames.isEmpty()) return DrawablePreview.Unsupported("animation-list", "No frames")
        return DrawablePreview.Frames(frames, oneShot = androidAttr(el, "oneshot").equals("true", ignoreCase = true))
    }

    /**
     * An `<animated-vector>`: its vector (an `android:drawable` reference or an inline `<aapt:attr>`) and the
     * animator of each `<target>`. A drawable that turns out not to be a vector is shown as whatever it is.
     */
    private fun parseAnimatedVector(el: Element, r: DrawableResolver, depth: Int): DrawablePreview {
        val base = inlineAttr(el, "drawable")?.let { parseElement(it, r, depth + 1) }
            ?: androidAttr(el, "drawable")?.let { resolveDrawableRef(it, r, depth) }
            ?: return DrawablePreview.Unsupported("animated-vector", "No vector")
        val vector = (base as? DrawablePreview.Vector)?.spec ?: return base
        val targets = ArrayList<AnimationTarget>()
        for (target in elements(el)) {
            if (target.tagName.substringAfterLast(':') != "target") continue
            val name = androidAttr(target, "name") ?: continue
            val animator = inlineAttr(target, "animation")?.let { parseAnimator(it, r, 0) }
                ?: androidAttr(target, "animation")?.let { ref -> xmlRoot(r.resolveXml(ref))?.let { parseAnimator(it, r, 0) } }
                ?: continue
            targets += AnimationTarget(name, animator)
        }
        return DrawablePreview.AnimatedVector(vector, targets)
    }

    private fun parseAnimator(el: Element, r: DrawableResolver, depth: Int): AnimatorSpec? {
        if (depth > MAX_DEPTH) return null
        return when (el.tagName.substringAfterLast(':')) {
            "set" -> elements(el).mapNotNull { parseAnimator(it, r, depth + 1) }
                .takeIf { it.isNotEmpty() }
                ?.let { AnimatorSpec.Set(it, sequential = androidAttr(el, "ordering") == "sequentially") }
            "objectAnimator" -> parseObjectAnimator(el, r)
            else -> null // a bare <animator> has no target property; view animations do not apply to vectors
        }
    }

    private fun parseObjectAnimator(el: Element, r: DrawableResolver): AnimatorSpec? {
        val duration = androidAttr(el, "duration")?.toLongOrNull()?.coerceAtLeast(0) ?: DEFAULT_DURATION_MS
        val offset = androidAttr(el, "startOffset")?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        val repeat = androidAttr(el, "repeatCount")?.let { if (it == "infinite") -1 else it.toIntOrNull() } ?: 0
        val reverse = androidAttr(el, "repeatMode") == "reverse"
        val interpolator = interpolatorOf(el, r) ?: InterpolatorSpec.DEFAULT
        val valueType = androidAttr(el, "valueType")

        fun property(name: String, keyframes: List<Keyframe>) = AnimatorSpec.Property(
            name, keyframes, duration, offset, if (repeat < -1) 0 else repeat, reverse, interpolator,
        )

        val holders = elements(el).filter { it.tagName.substringAfterLast(':') == "propertyValuesHolder" }
        val properties = if (holders.isEmpty()) {
            val name = androidAttr(el, "propertyName") ?: return null
            listOfNotNull(fromTo(el, name, valueType, r)?.let { property(name, it) })
        } else {
            holders.mapNotNull { h ->
                val name = androidAttr(h, "propertyName") ?: return@mapNotNull null
                val type = androidAttr(h, "valueType") ?: valueType
                val frames = keyframes(h, name, type, r) ?: fromTo(h, name, type, r) ?: return@mapNotNull null
                property(name, frames)
            }
        }
        return when (properties.size) {
            0 -> null
            1 -> properties[0]
            else -> AnimatorSpec.Set(properties, sequential = false)
        }
    }

    /** `valueFrom`/`valueTo` as two keyframes; a missing `valueFrom` starts from the property's current value. */
    private fun fromTo(el: Element, property: String, valueType: String?, r: DrawableResolver): List<Keyframe>? {
        val to = animatedValue(androidAttr(el, "valueTo"), property, valueType, r) ?: return null
        val from = animatedValue(androidAttr(el, "valueFrom"), property, valueType, r)
        return listOf(Keyframe(0f, from), Keyframe(1f, to))
    }

    private fun keyframes(el: Element, property: String, valueType: String?, r: DrawableResolver): List<Keyframe>? {
        val kfs = elements(el).filter { it.tagName.substringAfterLast(':') == "keyframe" }
        if (kfs.isEmpty()) return null
        return kfs.mapIndexed { i, kf ->
            val evenly = if (kfs.size == 1) 1f else i.toFloat() / (kfs.size - 1)
            Keyframe(
                fraction = androidAttr(kf, "fraction")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: evenly,
                value = animatedValue(androidAttr(kf, "value"), property, androidAttr(kf, "valueType") ?: valueType, r),
                interpolator = interpolatorOf(kf, r),
            )
        }.sortedBy { it.fraction }
    }

    private fun animatedValue(raw: String?, property: String, valueType: String?, r: DrawableResolver): AnimatedValue? {
        val s = raw?.trim()?.ifEmpty { null } ?: return null
        val type = valueType ?: when {
            property == "pathData" -> "pathType"
            property.endsWith("Color") || s.startsWith("#") || typeOf(s) == "color" -> "colorType"
            else -> "floatType"
        }
        return when (type) {
            "pathType" -> pathData(s, r)?.let { AnimatedValue.PathData(it) }
            "colorType" -> colorToken(s, r)?.let { AnimatedValue.Color(it) }
            else -> number(s, r)?.let { AnimatedValue.Number(it) }
        }
    }

    private fun number(s: String, r: DrawableResolver): Float? {
        s.toFloatOrNull()?.let { return it }
        if (!s.startsWith("@")) return null
        if (typeOf(s) == "dimen") return r.resolveDimenDp(s)
        return r.resolveValue(s)?.trim()?.toFloatOrNull()
    }

    /** An element's `android:interpolator`: inline, one of the framework's, or a project `@interpolator`. */
    private fun interpolatorOf(el: Element, r: DrawableResolver): InterpolatorSpec? {
        inlineAttr(el, "interpolator")?.let { return parseInterpolator(it) }
        val ref = androidAttr(el, "interpolator")?.trim()?.ifEmpty { null } ?: return null
        if (!ref.contains("android:")) xmlRoot(r.resolveXml(ref))?.let { parseInterpolator(it) }?.let { return it }
        return Interpolators.named(ref.substringAfterLast('/'))
    }

    private fun parseInterpolator(el: Element): InterpolatorSpec? {
        fun f(name: String, default: Float) = androidAttr(el, name)?.toFloatOrNull() ?: default
        return when (el.tagName.substringAfterLast(':')) {
            "linearInterpolator" -> InterpolatorSpec.LINEAR
            "accelerateInterpolator" -> InterpolatorSpec(InterpolatorKind.ACCELERATE, f("factor", 1f))
            "decelerateInterpolator" -> InterpolatorSpec(InterpolatorKind.DECELERATE, f("factor", 1f))
            "accelerateDecelerateInterpolator" -> InterpolatorSpec.DEFAULT
            "anticipateInterpolator" -> InterpolatorSpec(InterpolatorKind.ANTICIPATE, f("tension", 2f))
            "overshootInterpolator" -> InterpolatorSpec(InterpolatorKind.OVERSHOOT, f("tension", 2f))
            "anticipateOvershootInterpolator" ->
                InterpolatorSpec(InterpolatorKind.ANTICIPATE_OVERSHOOT, f("tension", 2f), f("extraTension", 1.5f))
            "bounceInterpolator" -> InterpolatorSpec(InterpolatorKind.BOUNCE)
            "cycleInterpolator" -> InterpolatorSpec(InterpolatorKind.CYCLE, f("cycles", 1f))
            "pathInterpolator" -> {
                androidAttr(el, "pathData")?.let { return InterpolatorSpec(InterpolatorKind.PATH, pathData = it) }
                val x1 = f("controlX1", 0f); val y1 = f("controlY1", 0f)
                val x2 = androidAttr(el, "controlX2")?.toFloatOrNull()
                val y2 = androidAttr(el, "controlY2")?.toFloatOrNull()
                // A quadratic curve (one control point) is the cubic with both controls two thirds of the way to it.
                val points = if (x2 != null && y2 != null) listOf(x1, y1, x2, y2)
                else listOf(x1 * 2f / 3f, y1 * 2f / 3f, (1f + 2f * x1) / 3f, (1f + 2f * y1) / 3f)
                InterpolatorSpec(InterpolatorKind.CUBIC, points = points)
            }
            else -> null
        }
    }

    /** The element an `<aapt:attr name="android:[attr]">` child inlines in place of a resource reference. */
    private fun inlineAttr(el: Element, attr: String): Element? = elements(el)
        .firstOrNull { it.tagName.substringAfterLast(':') == "attr" && it.getAttribute("name").substringAfterLast(':') == attr }
        ?.let { elements(it).firstOrNull() }

    private fun xmlRoot(text: String?): Element? = text?.let {
        runCatching { builder().parse(it.byteInputStream(Charsets.UTF_8)).documentElement }.getOrNull()
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

    /**
     * An `<adaptive-icon>` (API 26+ launcher icon): its `<background>` + `<foreground>` painted back-to-front,
     * like a two-layer [DrawablePreview.Layers] (the `<monochrome>` layer is themed-icon only, so ignored).
     * Each layer is an inline child or an `android:drawable` ref.
     */
    private fun parseAdaptiveIcon(el: Element, r: DrawableResolver, depth: Int): DrawablePreview {
        val layers = ArrayList<Layer>()
        for (child in elements(el)) {
            when (child.tagName.substringAfterLast(':')) {
                "background", "foreground" -> itemDrawable(child, r, depth)?.let { layers += Layer(it) }
            }
        }
        return if (layers.isEmpty()) DrawablePreview.Unsupported("adaptive-icon", "Empty adaptive icon")
        else DrawablePreview.Layers(layers, adaptive = true)
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
        inlineAttr(el, "drawable")?.let { return parseElement(it, r, depth + 1) }
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
