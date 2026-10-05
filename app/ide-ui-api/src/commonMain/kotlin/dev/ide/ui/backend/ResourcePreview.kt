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
        /** `android:name`, which an [AnimatedVector] target uses to animate [rootAlpha]. */
        val name: String? = null,
    ) : UiDrawable

    data class Layers(val layers: List<UiLayer>, val adaptive: Boolean = false) : UiDrawable
    data class States(val states: List<UiStateLayer>, val defaultLayer: UiDrawable?) : UiDrawable
    data class Bitmap(val resType: String, val resName: String, val filePath: String?) : UiDrawable

    /** An `<animation-list>`: [frames] in turn, looping unless [oneShot]. Drawn statically, its first frame. */
    data class Frames(val frames: List<UiFrame>, val oneShot: Boolean) : UiDrawable

    /** An `<animated-vector>`: [vector] and the animator of each named target. Drawn statically, [vector]. */
    data class AnimatedVector(val vector: Vector, val targets: List<UiAnimationTarget>) : UiDrawable

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
    val name: String? = null,
    /** The visible part of the path, as fractions of its length. */
    val trimPathStart: Float = 0f,
    val trimPathEnd: Float = 1f,
    val trimPathOffset: Float = 0f,
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
    val name: String? = null,
) : UiVectorNode

data class UiLayer(
    val drawable: UiDrawable,
    val insetLeftDp: Float,
    val insetTopDp: Float,
    val insetRightDp: Float,
    val insetBottomDp: Float,
)

data class UiStateLayer(val states: List<String>, val drawable: UiDrawable)

data class UiFrame(val drawable: UiDrawable, val durationMs: Int)

/** The animator of the group, path or vector named [name] in an [UiDrawable.AnimatedVector]. */
data class UiAnimationTarget(val name: String, val animator: UiAnimator)

sealed interface UiAnimator {
    data class Set(val children: List<UiAnimator>, val sequential: Boolean) : UiAnimator

    /** One animated property; [repeatCount] is -1 for forever, [reverse] plays alternate repeats backwards. */
    data class Property(
        val property: String,
        val keyframes: List<UiKeyframe>,
        val durationMs: Long,
        val startOffsetMs: Long,
        val repeatCount: Int,
        val reverse: Boolean,
        val interpolator: UiInterpolator,
    ) : UiAnimator
}

/** A value at [fraction] of an animator; null [value] = the property's own value. */
data class UiKeyframe(val fraction: Float, val value: UiAnimatedValue?, val interpolator: UiInterpolator?)

sealed interface UiAnimatedValue {
    data class Number(val value: Float) : UiAnimatedValue
    data class Color(val argb: Long) : UiAnimatedValue
    data class PathData(val data: String) : UiAnimatedValue
}

/**
 * An interpolator. [kind] is "linear" | "accelerate" | "decelerate" | "accelerate_decelerate" | "anticipate" |
 * "overshoot" | "anticipate_overshoot" | "bounce" | "cycle" | "cubic" (through [points] `x1, y1, x2, y2`) |
 * "path" (along [pathData]). [factor] is the factor, tension or cycle count the kind takes.
 */
data class UiInterpolator(
    val kind: String,
    val factor: Float = 1f,
    val extraTension: Float = 1.5f,
    val points: List<Float> = emptyList(),
    val pathData: String? = null,
)

/** One color-resource swatch. [argb] is null when the value couldn't be resolved (framework/unknown ref). */
data class UiColorEntry(val name: String, val rawValue: String, val argb: Long?)
