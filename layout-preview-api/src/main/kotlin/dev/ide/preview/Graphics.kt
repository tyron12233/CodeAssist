package dev.ide.preview

/** Paint fill mode, mirroring `android.graphics.Paint.Style`. */
enum class PaintStyle { FILL, STROKE, FILL_AND_STROKE }

/** A gradient fill for a paint. Colors are `0xAARRGGBB`; geometry is relative to the drawn rect. */
enum class GradientType { LINEAR, RADIAL, SWEEP }

data class Gradient(
    val type: GradientType,
    val colors: IntArray,
    val angleDeg: Int = 0,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radiusFraction: Float = 0.5f,
) {
    override fun equals(other: Any?): Boolean = other is Gradient && type == other.type && colors.contentEquals(other.colors) &&
        angleDeg == other.angleDeg && centerX == other.centerX && centerY == other.centerY && radiusFraction == other.radiusFraction
    override fun hashCode(): Int = type.hashCode() * 31 + colors.contentHashCode()
}

/**
 * A mutable paint. Colors are `0xAARRGGBB` ints (the same packing as `android.graphics.Color`). Created by
 * [RGraphics.newPaint]; the platform backend owns the concrete instance (a Compose/Skia `Paint`, or an
 * `android.graphics.Paint`).
 */
interface RPaint {
    var color: Int
    var style: PaintStyle
    var strokeWidth: Float
    var antiAlias: Boolean
    /** Text size in pixels (already scaled from sp). */
    var textSizePx: Float
    var bold: Boolean
    /** When set, fill shapes with this gradient instead of the flat [color]. */
    var gradient: Gradient?
}

/** An opaque platform path (parsed from `pathData`, or built by the backend). */
interface RPath

/**
 * Implemented by a platform [RCanvas] that wraps a native canvas, so a custom view's `onDraw` can draw into
 * the same surface: the device Bridge unwraps this to an `android.graphics.Canvas`, the desktop shim to a
 * Skiko canvas. Returns null when there's no native canvas (e.g. a headless recording canvas).
 */
interface NativeCanvasHolder {
    fun nativeCanvas(): Any?
}

/** An opaque platform image with its intrinsic pixel size. */
interface RImage {
    val width: Int
    val height: Int
}

/** Vertical text metrics for a paint, in pixels. [ascent] is the baseline offset from the line top. */
data class FontMetrics(val lineHeight: Float, val ascent: Float)

/**
 * The owned drawing surface. Renderers paint into this and nothing else — there is no `android.graphics`
 * import in a renderer. A platform backend implements it over Compose `DrawScope`, `android.graphics.Canvas`,
 * or Skiko, and the same backend instance is what a custom view's `onDraw` ultimately composites into (the
 * device backend hands the underlying `android.graphics.Canvas` to user code). Coordinates are pixels.
 */
interface RCanvas {
    fun save(): Int
    fun restore()
    fun translate(dx: Float, dy: Float)
    fun clipRect(l: Float, t: Float, r: Float, b: Float)

    fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: RPaint)
    fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: RPaint)
    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: RPaint)
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: RPaint)
    fun drawPath(path: RPath, paint: RPaint)
    /** Draw [img] into the rect, optionally tinted with [tintArgb] (`android:tint`). */
    fun drawImage(img: RImage, l: Float, t: Float, r: Float, b: Float, tintArgb: Int? = null)
    /** Draw [text] with its top-left at ([x],[y]) (not the baseline), matching Compose's `drawText`. */
    fun drawText(text: CharSequence, x: Float, y: Float, paint: RPaint)
}

/**
 * The factory the platform backend supplies for the pieces a renderer must *create* or *measure* (rather than
 * draw): a paint, a parsed path from Android `pathData`, and text metrics. Text metrics live here (not on
 * [RCanvas]) because the measure pass needs them and runs before any canvas exists. Image loading is
 * resource-driven and lives on [PreviewResources] instead.
 */
interface RGraphics {
    fun newPaint(): RPaint
    /** Parse Android vector `pathData` into a path; null if unparseable. */
    fun parsePath(pathData: String): RPath?
    /** Advance width in pixels of [text] in [paint]. */
    fun measureText(text: CharSequence, paint: RPaint): Float
    /** Line height + ascent in pixels for [paint]. */
    fun fontMetrics(paint: RPaint): FontMetrics
}
