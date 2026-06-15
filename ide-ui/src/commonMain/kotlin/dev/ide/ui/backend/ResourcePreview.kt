package dev.ide.ui.backend

/**
 * The neutral, render-ready drawable model handed to the resource-preview pane — a mirror of the engine's
 * `DrawablePreview`, with every reference already resolved. Colors are `0xAARRGGBB` longs, sizes are `dp`
 * floats, and vector geometry stays as the raw `pathData` string for the Compose renderer to parse.
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
        val paths: List<UiVectorPath>,
    ) : UiDrawable

    data class Layers(val layers: List<UiLayer>) : UiDrawable
    data class States(val states: List<UiStateLayer>, val defaultLayer: UiDrawable?) : UiDrawable
    data class Bitmap(val resType: String, val resName: String, val filePath: String?) : UiDrawable
    data class Unsupported(val rootTag: String, val message: String) : UiDrawable
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

data class UiVectorPath(
    val pathData: String,
    val fillColor: Long?,
    val strokeColor: Long?,
    val strokeWidthVp: Float,
    val fillAlpha: Float,
    val strokeAlpha: Float,
)

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
