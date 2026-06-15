package dev.ide.android.support.preview

/**
 * A render-ready, neutral model of an Android drawable — the parsed essence of a `res/drawable` (or
 * `res/color`) XML file, with every `@color`/`@dimen`/`@drawable` reference already resolved against the
 * project's resources (see [DrawableResolver]). It is deliberately UI-toolkit-agnostic: colors are
 * `0xAARRGGBB` longs, sizes are `dp` floats, and vector geometry stays as the raw SVG-ish `pathData`
 * string so the host renderer (Compose `Path`, an Android `Canvas`, …) can draw it however it likes.
 *
 * The host (`ide-core`) maps this to its own neutral UI DTOs; the model never leaks JAXP or `android.*`.
 */
sealed interface DrawablePreview {

    /** A flat `<color>`/`<shape android:shape="rectangle"><solid/></shape>` fill. */
    data class SolidColor(val color: Long) : DrawablePreview

    /** A `<shape>` (rectangle/oval/line/ring) with an optional solid or gradient fill + stroke + corners. */
    data class Shape(val spec: ShapeSpec) : DrawablePreview

    /** A `<vector>` — viewport + one or more SVG paths. */
    data class Vector(val spec: VectorSpec) : DrawablePreview

    /** A `<layer-list>` — drawables painted back-to-front, each optionally inset. */
    data class Layers(val layers: List<Layer>) : DrawablePreview

    /** A `<selector>` (state list) — the representative [defaultLayer] is what a static preview shows. */
    data class States(val states: List<StateLayer>, val defaultLayer: DrawablePreview?) : DrawablePreview

    /**
     * A `<bitmap>`/`<nine-patch>` or an `@drawable`/`@mipmap` ref to an actual image file. [filePath] is the
     * resolved absolute path (when known) so the host can load + decode the image bytes for rendering.
     */
    data class BitmapRef(val resType: String, val resName: String, val filePath: String? = null) : DrawablePreview

    /** A root tag we model the structure of but can't fully render (e.g. `<animated-vector>`). */
    data class Unsupported(val rootTag: String, val message: String) : DrawablePreview
}

enum class ShapeKind { RECTANGLE, OVAL, LINE, RING }

enum class GradientKind { LINEAR, RADIAL, SWEEP }

/**
 * A `<gradient>`. [startColor]/[endColor] are required; [centerColor] adds a 3-stop gradient. [angle] is
 * the linear sweep in degrees (Android allows multiples of 45). [centerX]/[centerY] are 0..1 fractions of
 * the bounds; [radiusFraction] is the radial radius as a fraction of the min bound dimension.
 */
data class GradientSpec(
    val kind: GradientKind,
    val startColor: Long,
    val centerColor: Long?,
    val endColor: Long,
    val angle: Int = 0,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radiusFraction: Float = 0.5f,
)

/**
 * A `<shape>`'s geometry + paint. Corner radii are per-corner (a uniform `<corners android:radius>` fills
 * all four). [intrinsicWidthDp]/[intrinsicHeightDp] come from `<size>` (0 = unspecified → fill the canvas).
 */
data class ShapeSpec(
    val shape: ShapeKind,
    val solidColor: Long? = null,
    val gradient: GradientSpec? = null,
    val strokeColor: Long? = null,
    val strokeWidthDp: Float = 0f,
    val dashWidthDp: Float = 0f,
    val dashGapDp: Float = 0f,
    val cornerTopLeftDp: Float = 0f,
    val cornerTopRightDp: Float = 0f,
    val cornerBottomRightDp: Float = 0f,
    val cornerBottomLeftDp: Float = 0f,
    val intrinsicWidthDp: Float = 0f,
    val intrinsicHeightDp: Float = 0f,
    /** Ring geometry (only meaningful when [shape] == RING): inner radius + thickness, as bound fractions. */
    val innerRadiusFraction: Float = 0.33f,
    val thicknessFraction: Float = 0.22f,
)

/** One `<path>` of a `<vector>`. Stroke width is in viewport units (matched to the `pathData` coordinates). */
data class VectorPath(
    val pathData: String,
    val fillColor: Long? = null,
    val strokeColor: Long? = null,
    val strokeWidthVp: Float = 0f,
    val fillAlpha: Float = 1f,
    val strokeAlpha: Float = 1f,
)

/**
 * A `<vector>`. [widthDp]/[heightDp] are the intrinsic size; the [viewportWidth]/[viewportHeight] define the
 * coordinate space the [paths] are drawn in (the renderer scales viewport → bounds). [rootAlpha] is the
 * whole-drawable alpha.
 */
data class VectorSpec(
    val widthDp: Float,
    val heightDp: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val rootAlpha: Float = 1f,
    val paths: List<VectorPath>,
)

/** A `<layer-list>` item: its drawable + optional insets (dp). */
data class Layer(
    val drawable: DrawablePreview,
    val insetLeftDp: Float = 0f,
    val insetTopDp: Float = 0f,
    val insetRightDp: Float = 0f,
    val insetBottomDp: Float = 0f,
)

/** A `<selector>` item: the states it applies to (e.g. `state_pressed`, `state_checked`) + its drawable. */
data class StateLayer(val states: List<String>, val drawable: DrawablePreview)

/**
 * Resolves the references a drawable can contain against the project's merged resources. All methods return
 * null when the reference is a framework (`@android:…`) or otherwise unknown resource, so the parser can
 * fall back to a neutral placeholder rather than guessing. [NONE] resolves nothing (literal values only).
 */
interface DrawableResolver {
    /** `@color/x` (resolved transitively) → `0xAARRGGBB`, or null. */
    fun resolveColor(ref: String): Long?

    /** `@dimen/x` → a `dp` value, or null. */
    fun resolveDimenDp(ref: String): Float?

    /** `@drawable/x` / `@mipmap/x` → its source (nested XML to recurse into, or a bitmap file), or null. */
    fun resolveDrawable(ref: String): ResolvedDrawable?

    companion object {
        val NONE: DrawableResolver = object : DrawableResolver {
            override fun resolveColor(ref: String): Long? = null
            override fun resolveDimenDp(ref: String): Float? = null
            override fun resolveDrawable(ref: String): ResolvedDrawable? = null
        }
    }
}

/** What a `@drawable/x` reference points at. */
sealed interface ResolvedDrawable {
    /** A nested XML drawable — its text, to be parsed recursively. */
    data class Xml(val text: String) : ResolvedDrawable
    /** An image file (png/webp/jpg/…) — rendered by loading the bytes, not by parsing. [path] is its absolute path. */
    data class BitmapFile(val resType: String, val resName: String, val path: String? = null) : ResolvedDrawable
}

/**
 * Android color-value parsing — the `#RGB` / `#ARGB` / `#RRGGBB` / `#AARRGGBB` literal forms aapt accepts,
 * to the canonical `0xAARRGGBB` long. A small table of the most common framework `@android:color/…`
 * constants is included so previews of stock selectors (e.g. `@android:color/transparent`) aren't blank.
 */
object AndroidColor {

    /** Parse a literal `#…` color to `0xAARRGGBB`, or null if it isn't a well-formed hex color. */
    fun parseHex(raw: String): Long? {
        val s = raw.trim()
        if (!s.startsWith("#")) return null
        val h = s.substring(1)
        if (h.any { hexDigit(it) < 0 }) return null
        return when (h.length) {
            3 -> argb(0xFF, expand(h[0]), expand(h[1]), expand(h[2]))           // #RGB
            4 -> argb(expand(h[0]), expand(h[1]), expand(h[2]), expand(h[3]))   // #ARGB
            6 -> 0xFF000000L or h.toLong(16)                                     // #RRGGBB
            8 -> h.toLong(16)                                                    // #AARRGGBB
            else -> null
        }
    }

    /** A common framework color constant (`android:color/transparent`, `white`, `black`), or null. */
    fun framework(name: String): Long? = FRAMEWORK[name]

    private val FRAMEWORK = mapOf(
        "transparent" to 0x00000000L,
        "white" to 0xFFFFFFFFL,
        "black" to 0xFF000000L,
        "darker_gray" to 0xFFAAAAAAL,
        "background_dark" to 0xFF000000L,
        "background_light" to 0xFFFFFFFFL,
        "holo_blue_light" to 0xFF33B5E5L,
        "holo_blue_dark" to 0xFF0099CCL,
        "holo_green_light" to 0xFF99CC00L,
        "holo_red_light" to 0xFFFF4444L,
        "holo_orange_light" to 0xFFFFBB33L,
    )

    private fun expand(c: Char): Int = hexDigit(c).let { it * 16 + it }    // 0xF → 0xFF
    private fun argb(a: Int, r: Int, g: Int, b: Int): Long =
        ((a.toLong() and 0xFF) shl 24) or ((r.toLong() and 0xFF) shl 16) or
            ((g.toLong() and 0xFF) shl 8) or (b.toLong() and 0xFF)

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
