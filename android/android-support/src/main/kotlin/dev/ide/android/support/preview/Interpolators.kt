package dev.ide.android.support.preview

/**
 * The framework's interpolators by resource name: `@android:interpolator/x`, `@android:anim/x_interpolator`,
 * and the `fast_out_slow_in` family that AndroidX and Material ship under their own `@interpolator` names. A
 * project interpolator file is parsed instead; this is the fallback for the ones a project only references.
 */
internal object Interpolators {

    private val FAST_OUT_SLOW_IN = cubic(0.4f, 0f, 0.2f, 1f)
    private val FAST_OUT_LINEAR_IN = cubic(0.4f, 0f, 1f, 1f)
    private val LINEAR_OUT_SLOW_IN = cubic(0f, 0f, 0.2f, 1f)
    private val FAST_OUT_EXTRA_SLOW_IN = InterpolatorSpec(
        InterpolatorKind.PATH,
        pathData = "M 0,0 C 0.05,0 0.133333,0.06 0.166666,0.4 C 0.208333,0.82 0.25,1 1,1",
    )

    fun named(name: String): InterpolatorSpec? {
        val n = name.removeSuffix("_interpolator").removePrefix("mtrl_")
        return when (n) {
            "linear" -> InterpolatorSpec.LINEAR
            "accelerate", "accelerate_quad" -> InterpolatorSpec(InterpolatorKind.ACCELERATE, 1f)
            "accelerate_cubic" -> InterpolatorSpec(InterpolatorKind.ACCELERATE, 1.5f)
            "accelerate_quint" -> InterpolatorSpec(InterpolatorKind.ACCELERATE, 2.5f)
            "decelerate", "decelerate_quad" -> InterpolatorSpec(InterpolatorKind.DECELERATE, 1f)
            "decelerate_cubic" -> InterpolatorSpec(InterpolatorKind.DECELERATE, 1.5f)
            "decelerate_quint" -> InterpolatorSpec(InterpolatorKind.DECELERATE, 2.5f)
            "accelerate_decelerate" -> InterpolatorSpec.DEFAULT
            "anticipate" -> InterpolatorSpec(InterpolatorKind.ANTICIPATE, 2f)
            "overshoot" -> InterpolatorSpec(InterpolatorKind.OVERSHOOT, 2f)
            "anticipate_overshoot" -> InterpolatorSpec(InterpolatorKind.ANTICIPATE_OVERSHOOT, 2f, 1.5f)
            "bounce" -> InterpolatorSpec(InterpolatorKind.BOUNCE)
            "cycle" -> InterpolatorSpec(InterpolatorKind.CYCLE, 1f)
            "fast_out_slow_in" -> FAST_OUT_SLOW_IN
            "fast_out_linear_in" -> FAST_OUT_LINEAR_IN
            "linear_out_slow_in" -> LINEAR_OUT_SLOW_IN
            "fast_out_extra_slow_in" -> FAST_OUT_EXTRA_SLOW_IN
            else -> null
        }
    }

    private fun cubic(x1: Float, y1: Float, x2: Float, y2: Float) =
        InterpolatorSpec(InterpolatorKind.CUBIC, points = listOf(x1, y1, x2, y2))
}
