package dev.ide.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator as MaterialLinearProgressIndicator

/**
 * Progress indicators that keep moving when the platform has turned motion off.
 *
 * These shadow the Material 3 composables of the same name, and a call site reaches them by importing
 * `dev.ide.ui.components` instead of `androidx.compose.material3`. A **determinate** indicator is handed
 * straight to Material; so is an indeterminate one whenever animation is enabled, so nothing about the
 * app's appearance changes on a device with motion on.
 *
 * What they exist for is the case where it is off. Material's indeterminate indicators are built on
 * `rememberInfiniteTransition`, and an infinite transition reads [MotionDurationScale] from the
 * composition's coroutine context: at a scale of zero it calls `skipToEnd()` on every child and suspends
 * until the scale goes positive again. The spinner parks on its last frame and stays there.
 *
 * Two real settings produce that scale. On iOS, Compose Multiplatform maps
 * `UIAccessibilityIsReduceMotionEnabled()` to a scale of exactly 0; on Android, Developer options ->
 * "Animator duration scale: off" does the same. On either, every busy state in the IDE — indexing, a
 * build, resolving a dependency, installing from the store — renders as a frozen arc that is
 * indistinguishable from a hang.
 *
 * Honoring reduce-motion is right for decoration, and the app's sheets and transitions still snap the way
 * the setting asks. A progress indicator is not decoration: it is the only evidence that work is still
 * running, which is why iOS keeps its own `UIActivityIndicatorView` spinning under the same setting. So
 * when the scale is zero these fall back to an arc (or bar) driven straight off the frame clock, which no
 * duration scale is applied to.
 */
@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = ProgressIndicatorDefaults.circularIndeterminateTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularIndeterminateStrokeCap,
) {
    if (motionIsAnimated()) {
        MaterialCircularProgressIndicator(modifier, color, strokeWidth, trackColor, strokeCap)
    } else {
        UnscaledCircularIndicator(modifier, color, strokeWidth, trackColor, strokeCap)
    }
}

/** The determinate indicator, which animates nothing of its own and so is Material's unchanged. */
@OptIn(ExperimentalMaterial3Api::class) // the track gap default, which is carried through rather than set
@Composable
fun CircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = ProgressIndicatorDefaults.circularDeterminateTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularDeterminateStrokeCap,
    gapSize: Dp = ProgressIndicatorDefaults.CircularIndicatorTrackGapSize,
) = MaterialCircularProgressIndicator(
    progress, modifier, color, strokeWidth, trackColor, strokeCap, gapSize,
)

/** The indeterminate linear bar; see [CircularProgressIndicator] for why it is not always Material's. */
@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
) {
    if (motionIsAnimated()) {
        MaterialLinearProgressIndicator(modifier, color, trackColor, strokeCap)
    } else {
        UnscaledLinearIndicator(modifier, color, trackColor, strokeCap)
    }
}

/**
 * The determinate bar, which animates nothing of its own and so is Material's unchanged.
 *
 * [drawStopIndicator] is nullable rather than defaulted because Material's own default for it draws the
 * track's stop dot through a helper on `ProgressIndicatorDefaults`; leaving it null hands the call to the
 * overload that supplies that default itself, so the dot stays Material's to define.
 */
@OptIn(ExperimentalMaterial3Api::class) // the track gap default, which is carried through rather than set
@Composable
fun LinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
    gapSize: Dp = ProgressIndicatorDefaults.LinearIndicatorTrackGapSize,
    drawStopIndicator: (DrawScope.() -> Unit)? = null,
) = if (drawStopIndicator == null) {
    MaterialLinearProgressIndicator(progress, modifier, color, trackColor, strokeCap, gapSize)
} else {
    MaterialLinearProgressIndicator(
        progress, modifier, color, trackColor, strokeCap, gapSize, drawStopIndicator,
    )
}

/**
 * Whether animations play at all right now.
 *
 * [MotionDurationScale] rides in the coroutine context rather than a composition local, and
 * `rememberCoroutineScope()` is the one public way to read the context a `LaunchedEffect` in this
 * composition would inherit — the same context Material's infinite transitions read. The scale is backed
 * by snapshot state on both hosts that set it, so reading it here re-composes if the user changes the
 * setting while the app is open.
 */
@Composable
private fun motionIsAnimated(): Boolean {
    val scope = rememberCoroutineScope()
    return (scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
}

/**
 * Frame time in milliseconds, as a state that only the draw phase reads.
 *
 * `withFrameMillis` is the raw frame clock: it carries no duration scale, which is the whole point of
 * driving these from it. Keeping the value in a [State] that is read inside a `Canvas` block means a
 * frame invalidates the drawing and not the composition.
 */
@Composable
private fun rememberFrameMillis(): State<Long> {
    val millis = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { millis.value = it }
    }
    return millis
}

@Composable
private fun UnscaledCircularIndicator(
    modifier: Modifier,
    color: Color,
    strokeWidth: Dp,
    trackColor: Color,
    strokeCap: StrokeCap,
) {
    val frame = rememberFrameMillis()
    Canvas(modifier.progressSemantics().size(CircularIndicatorDiameter)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = strokeCap)
        val inset = stroke.width / 2f
        val box = Size(size.width - stroke.width, size.height - stroke.width)
        if (trackColor.alpha > 0f) {
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), box, style = stroke)
        }
        // A head that runs at a constant rate with a sweep that breathes behind it: the arc lengthens
        // through the first half of a turn and is reeled back in through the second.
        val phase = (frame.value % CircularCycleMillis) / CircularCycleMillis.toFloat()
        val sweep = MinSweepDegrees + (MaxSweepDegrees - MinSweepDegrees) *
            (0.5f - 0.5f * cos(2f * PI.toFloat() * phase))
        drawArc(color, phase * 360f - 90f, sweep, false, Offset(inset, inset), box, style = stroke)
    }
}

@Composable
private fun UnscaledLinearIndicator(
    modifier: Modifier,
    color: Color,
    trackColor: Color,
    strokeCap: StrokeCap,
) {
    val frame = rememberFrameMillis()
    Canvas(modifier.progressSemantics().size(LinearIndicatorWidth, LinearIndicatorHeight)) {
        drawBar(0f, 1f, trackColor, strokeCap)
        // One segment per cycle: the head pulls away from a standing start, then the tail catches it up,
        // so the bar grows out of the left edge and is swallowed by the right.
        val phase = (frame.value % LinearCycleMillis) / LinearCycleMillis.toFloat()
        val head = Motion.soft.transform((phase * 1.5f).coerceAtMost(1f))
        val tail = Motion.soft.transform(((phase - LinearTailLag) * 1.5f).coerceAtLeast(0f))
        if (head > tail) drawBar(tail, head, color, strokeCap)
    }
}

/** One horizontal run of the track, from [start] to [end] as fractions of the width. */
private fun DrawScope.drawBar(start: Float, end: Float, color: Color, strokeCap: StrokeCap) {
    if (color.alpha <= 0f) return
    val y = size.height / 2f
    // A round cap paints half a stroke width past each end, so the run is inset to stay on the track.
    val cap = if (strokeCap == StrokeCap.Butt) 0f else size.height / 2f
    val from = start * size.width + cap
    val to = end * size.width - cap
    if (to <= from) return
    drawLine(color, Offset(from, y), Offset(to, y), strokeWidth = size.height, cap = strokeCap)
}

/** Material's own indicator metrics, so a fallback occupies exactly the space the real one would. */
private val CircularIndicatorDiameter = 40.dp
private val LinearIndicatorWidth = 240.dp
private val LinearIndicatorHeight = 4.dp

private const val CircularCycleMillis = 1332L
private const val MinSweepDegrees = 20f
private const val MaxSweepDegrees = 280f
private const val LinearCycleMillis = 1800L

/** How far into a cycle the tail starts, as a fraction of it. */
private const val LinearTailLag = 1f / 3f
