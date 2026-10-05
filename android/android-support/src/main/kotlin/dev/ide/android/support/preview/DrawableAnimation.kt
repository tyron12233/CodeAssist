package dev.ide.android.support.preview

/** One `<item>` of an `<animation-list>`: its drawable and how long it stays up. */
data class Frame(val drawable: DrawablePreview, val durationMs: Int)

/** One `<target>` of an `<animated-vector>`: the `android:name` of a group, path or the vector, and its animator. */
data class AnimationTarget(val name: String, val animator: AnimatorSpec)

/**
 * A property animator from `res/animator` (or inlined through `<aapt:attr>`), reduced to what a preview needs
 * to play it: which property moves, through which values, and when.
 */
sealed interface AnimatorSpec {

    /** A `<set>`: its children all at once, or one after another when [sequential]. */
    data class Set(val children: List<AnimatorSpec>, val sequential: Boolean) : AnimatorSpec

    /**
     * One property of an `<objectAnimator>`. An animator with several `<propertyValuesHolder>`s becomes a [Set]
     * of these sharing the same timing. [repeatCount] is -1 for `infinite`; [reverse] is `repeatMode="reverse"`.
     */
    data class Property(
        val property: String,
        val keyframes: List<Keyframe>,
        val durationMs: Long,
        val startOffsetMs: Long = 0,
        val repeatCount: Int = 0,
        val reverse: Boolean = false,
        val interpolator: InterpolatorSpec = InterpolatorSpec.DEFAULT,
    ) : AnimatorSpec
}

/**
 * A value at [fraction] of an animator's duration. A null [value] means "the property's current value", which is
 * what an animator without `valueFrom` starts from. [interpolator] shapes the interval that ends here.
 */
data class Keyframe(val fraction: Float, val value: AnimatedValue?, val interpolator: InterpolatorSpec? = null)

sealed interface AnimatedValue {
    data class Number(val value: Float) : AnimatedValue
    data class Color(val argb: Long) : AnimatedValue
    data class PathData(val data: String) : AnimatedValue
}

enum class InterpolatorKind {
    LINEAR, ACCELERATE, DECELERATE, ACCELERATE_DECELERATE, ANTICIPATE, OVERSHOOT, ANTICIPATE_OVERSHOOT,
    BOUNCE, CYCLE,

    /** A cubic Bezier from (0,0) to (1,1) through the two control points in [InterpolatorSpec.points]. */
    CUBIC,

    /** A `<pathInterpolator android:pathData>` curve from (0,0) to (1,1). */
    PATH,
}

/**
 * An interpolator: [factor] is the accelerate/decelerate factor, the anticipate/overshoot tension, or the cycle
 * count; [extraTension] is `anticipateOvershoot`'s multiplier. [points] are [InterpolatorKind.CUBIC]'s
 * `x1, y1, x2, y2` and [pathData] is [InterpolatorKind.PATH]'s curve.
 */
data class InterpolatorSpec(
    val kind: InterpolatorKind,
    val factor: Float = 1f,
    val extraTension: Float = 1.5f,
    val points: List<Float> = emptyList(),
    val pathData: String? = null,
) {
    companion object {
        /** An `<objectAnimator>` without `android:interpolator` accelerates then decelerates. */
        val DEFAULT = InterpolatorSpec(InterpolatorKind.ACCELERATE_DECELERATE)
        val LINEAR = InterpolatorSpec(InterpolatorKind.LINEAR)
    }
}
