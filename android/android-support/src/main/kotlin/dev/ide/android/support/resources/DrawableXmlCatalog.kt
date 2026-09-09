package dev.ide.android.support.resources

import dev.ide.android.support.metadata.AttributeSpec

/**
 * A curated schema of the Android drawable/color-state-list XML grammar — `<shape>`, `<selector>`,
 * `<layer-list>`, `<vector>`, `<ripple>`, `<inset>`, `<bitmap>`, … and their children/attributes. Like the
 * `attrs.xml`-derived layout metadata, this grammar lives in the framework's compiled resources (not in a
 * styleable we can read from `android.jar`), and is small + stable across API levels, so a hand-built table
 * is the right tool. Reuses [AttributeSpec] so the XML completion adapter treats values identically
 * (enums, booleans, `@color`/`@drawable` references).
 */
object DrawableXmlCatalog {

    data class Element(
        val tag: String,
        val attributes: List<AttributeSpec> = emptyList(),
        val children: List<String> = emptyList(),
    )

    /** The drawable/color root tags a `res/drawable` or `res/color` document may start with. */
    val ROOTS: List<String> = listOf(
        "shape", "selector", "layer-list", "vector", "ripple", "inset", "level-list", "transition",
        "animated-vector", "bitmap", "nine-patch", "clip", "scale", "rotate", "color",
    )

    /** Whether [path] is a drawable/color resource XML the drawable grammar applies to. */
    fun appliesTo(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (!p.endsWith(".xml")) return false
        val folder = p.substringBeforeLast('/').substringAfterLast('/').substringBefore('-')
        return folder == "drawable" || folder == "color" || folder == "mipmap"
    }

    /** Element tags valid inside [parentTag] (null = the document root → all [ROOTS]). */
    fun childrenOf(parentTag: String?): List<String> {
        if (parentTag == null) return ROOTS
        return elements[parentTag.substringAfterLast(':')]?.children ?: emptyList()
    }

    /** Attributes declarable on [tag]. */
    fun attributesFor(tag: String?): List<AttributeSpec> =
        elements[tag?.substringAfterLast(':')]?.attributes ?: emptyList()

    /** A single attribute's spec (for value completion), or null. */
    fun attribute(tag: String?, attributeName: String?): AttributeSpec? =
        attributesFor(tag).firstOrNull { it.name == attributeName }

    // --- attribute builders ----------------------------------------------------------------------------

    private fun color(n: String) = AttributeSpec("android:$n", resourceTypes = listOf(ResourceType.COLOR))
    private fun drawableRef(n: String = "drawable") =
        AttributeSpec("android:$n", resourceTypes = listOf(ResourceType.DRAWABLE, ResourceType.MIPMAP, ResourceType.COLOR))
    private fun dimen(n: String) = AttributeSpec("android:$n", resourceTypes = listOf(ResourceType.DIMEN))
    private fun num(n: String) = AttributeSpec("android:$n")
    private fun enum(n: String, vararg values: String) = AttributeSpec("android:$n", enumValues = values.toList())
    private fun flags(n: String, vararg values: String) = AttributeSpec("android:$n", flags = values.toList())
    private fun bool(n: String) = AttributeSpec("android:$n", boolean = true)
    private fun id(n: String = "id") = AttributeSpec("android:$n", resourceTypes = listOf(ResourceType.ID))

    private val GRAVITY = arrayOf(
        "top", "bottom", "left", "right", "center", "center_vertical", "center_horizontal",
        "fill", "fill_vertical", "fill_horizontal", "start", "end", "clip_vertical", "clip_horizontal",
    )
    private val STATE_FLAGS = listOf(
        "state_pressed", "state_focused", "state_selected", "state_checkable", "state_checked",
        "state_enabled", "state_activated", "state_window_focused", "state_hovered",
    )

    private fun itemElement(extra: List<AttributeSpec>) =
        Element("item", attributes = listOf(drawableRef(), color("color"), id()) + extra, children = ROOTS)

    private val elements: Map<String, Element> = listOf(
        Element(
            "shape",
            attributes = listOf(
                enum("shape", "rectangle", "oval", "line", "ring"),
                dimen("innerRadius"), num("innerRadiusRatio"), dimen("thickness"), num("thicknessRatio"),
                bool("useLevel"),
            ),
            children = listOf("solid", "gradient", "stroke", "corners", "size", "padding"),
        ),
        Element("solid", attributes = listOf(color("color"))),
        Element(
            "gradient",
            attributes = listOf(
                enum("type", "linear", "radial", "sweep"),
                color("startColor"), color("centerColor"), color("endColor"),
                num("angle"), num("centerX"), num("centerY"), AttributeSpec("android:gradientRadius"),
                bool("useLevel"),
            ),
        ),
        Element("stroke", attributes = listOf(dimen("width"), color("color"), dimen("dashWidth"), dimen("dashGap"))),
        Element(
            "corners",
            attributes = listOf(
                dimen("radius"), dimen("topLeftRadius"), dimen("topRightRadius"),
                dimen("bottomLeftRadius"), dimen("bottomRightRadius"),
            ),
        ),
        Element("size", attributes = listOf(dimen("width"), dimen("height"))),
        Element("padding", attributes = listOf(dimen("left"), dimen("top"), dimen("right"), dimen("bottom"))),

        Element("selector", attributes = listOf(bool("constantSize"), bool("dither"), bool("variablePadding")), children = listOf("item")),
        Element("layer-list", attributes = emptyList(), children = listOf("item")),
        Element("level-list", attributes = emptyList(), children = listOf("item")),
        Element("transition", attributes = emptyList(), children = listOf("item")),
        Element("ripple", attributes = listOf(color("color")), children = listOf("item")),

        Element(
            "vector",
            attributes = listOf(
                AttributeSpec("android:name"), dimen("width"), dimen("height"),
                num("viewportWidth"), num("viewportHeight"), num("alpha"), color("tint"),
                enum("tintMode", "src_over", "src_in", "src_atop", "multiply", "screen", "add"),
                bool("autoMirrored"),
            ),
            children = listOf("path", "group", "clip-path"),
        ),
        Element(
            "path",
            attributes = listOf(
                AttributeSpec("android:name"), AttributeSpec("android:pathData"),
                color("fillColor"), color("strokeColor"), num("strokeWidth"),
                num("strokeAlpha"), num("fillAlpha"),
                enum("fillType", "nonZero", "evenOdd"),
                enum("strokeLineCap", "butt", "round", "square"),
                enum("strokeLineJoin", "miter", "round", "bevel"),
                num("strokeMiterLimit"),
            ),
        ),
        Element(
            "group",
            attributes = listOf(
                AttributeSpec("android:name"), num("rotation"), num("pivotX"), num("pivotY"),
                num("scaleX"), num("scaleY"), num("translateX"), num("translateY"),
            ),
            children = listOf("path", "group", "clip-path"),
        ),
        Element("clip-path", attributes = listOf(AttributeSpec("android:name"), AttributeSpec("android:pathData"))),

        Element(
            "bitmap",
            attributes = listOf(
                drawableRef("src"), flags("gravity", *GRAVITY), bool("antialias"), bool("dither"), bool("filter"),
                enum("tileMode", "disabled", "clamp", "repeat", "mirror"),
                color("tint"),
            ),
        ),
        Element("nine-patch", attributes = listOf(drawableRef("src"), bool("dither"), color("tint"))),
        Element("inset", attributes = listOf(drawableRef(), dimen("inset"), dimen("insetLeft"), dimen("insetTop"), dimen("insetRight"), dimen("insetBottom"))),
        Element("clip", attributes = listOf(drawableRef(), flags("clipOrientation", "horizontal", "vertical"), flags("gravity", *GRAVITY)), children = listOf("shape", "selector")),
        Element("scale", attributes = listOf(drawableRef(), num("scaleWidth"), num("scaleHeight"), flags("scaleGravity", *GRAVITY), num("level"))),
        Element("rotate", attributes = listOf(drawableRef(), num("fromDegrees"), num("toDegrees"), num("pivotX"), num("pivotY"))),
        Element("color", attributes = listOf(color("color"))),
        Element("animated-vector", attributes = listOf(drawableRef())),

        // The selector/layer-list/level-list item — its attribute set varies slightly, but the union is fine
        // for completion; offer the state_* flags (selector) + insets/levels (layer/level) together.
        itemElement(
            listOf(
                dimen("left"), dimen("top"), dimen("right"), dimen("bottom"),
                dimen("width"), dimen("height"), flags("gravity", *GRAVITY),
                num("minLevel"), num("maxLevel"),
            ) + STATE_FLAGS.map { bool(it) },
        ),
    ).associateBy { it.tag }
}
