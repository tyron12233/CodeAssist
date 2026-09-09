package dev.ide.ui.backend

/**
 * The neutral, render-ready drawable model handed to the resource-preview pane — a mirror of the engine's
 * `DrawablePreview`, with every reference already resolved. Colors are `0xAARRGGBB` longs, sizes are `dp`
 * floats, and vector geometry stays as the raw `pathData` string for the Compose renderer to parse.
 * Enum-ish fields are plain strings so this module stays free of both the engine's and Compose's types.
 */
sealed interface UiDrawable {
    data class SolidColor(val color: Long) : UiDrawable

    data class Shape(
        val shape: String, // "rectangle" | "oval" | "line" | "ring"
        val solidColor: Long?,
        val gradient: UiGradient?,
        val strokeColor: Long?,
        val strokeWidthDp: Float,
        val dashWidthDp: Float,
        val dashGapDp: Float,
        val cornerTopLeftDp: Float,
        val cornerTopRightDp: Float,
        val cornerBottomRightDp: Float,
        val cornerBottomLeftDp: Float,
        val intrinsicWidthDp: Float,
        val intrinsicHeightDp: Float,
        val innerRadiusFraction: Float,
        val thicknessFraction: Float,
    ) : UiDrawable

    data class Vector(
        val widthDp: Float,
        val heightDp: Float,
        val viewportWidth: Float,
        val viewportHeight: Float,
        val rootAlpha: Float,
        val nodes: List<UiVectorNode>,
    ) : UiDrawable

    data class Layers(val layers: List<UiLayer>) : UiDrawable
    data class States(val states: List<UiStateLayer>, val defaultLayer: UiDrawable?) : UiDrawable
    data class Bitmap(val resType: String, val resName: String, val filePath: String?) : UiDrawable
    data class Unsupported(val rootTag: String, val message: String) : UiDrawable
}

/**
 * A project's launcher icon for the picker: either encoded raster bytes (PNG/WebP/…, decoded by the host) or
 * a render-ready [UiDrawable] (a vector / layer-list / adaptive icon drawn on a Compose canvas).
 */
sealed interface UiProjectIcon {
    data class Raster(val bytes: ByteArray) : UiProjectIcon
    data class Drawable(val drawable: UiDrawable) : UiProjectIcon
}

data class UiGradient(
    val kind: String, // "linear" | "radial" | "sweep"
    val startColor: Long,
    val centerColor: Long?,
    val endColor: Long,
    val angle: Int,
    val centerX: Float,
    val centerY: Float,
    val radiusFraction: Float,
)

/** A node of a [UiDrawable.Vector]'s tree: a drawn [UiVectorPath], or a [UiVectorGroup] over its children. */
sealed interface UiVectorNode

data class UiVectorPath(
    val pathData: String,
    val fillColor: Long?,
    val strokeColor: Long?,
    val strokeWidthVp: Float,
    val fillAlpha: Float,
    val strokeAlpha: Float,
    /** "nonZero" | "evenOdd": which side of a self-intersecting outline counts as inside. */
    val fillRule: String = "nonZero",
    /** "butt" | "round" | "square" */
    val strokeCap: String = "butt",
    /** "miter" | "round" | "bevel" */
    val strokeJoin: String = "miter",
    val strokeMiter: Float = 4f,
) : UiVectorNode

/**
 * A `<group>`: scale, then rotate, then translate over its [children], with scale/rotate about the pivot.
 * All values are in the vector's viewport units. [clipPathData] restricts the children to that outline.
 */
data class UiVectorGroup(
    val children: List<UiVectorNode>,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
    val clipPathData: String? = null,
) : UiVectorNode

data class UiLayer(
    val drawable: UiDrawable,
    val insetLeftDp: Float,
    val insetTopDp: Float,
    val insetRightDp: Float,
    val insetBottomDp: Float,
)

data class UiStateLayer(val states: List<String>, val drawable: UiDrawable)

/** One color-resource swatch. [argb] is null when the value couldn't be resolved (framework/unknown ref). */
data class UiColorEntry(val name: String, val rawValue: String, val argb: Long?)
