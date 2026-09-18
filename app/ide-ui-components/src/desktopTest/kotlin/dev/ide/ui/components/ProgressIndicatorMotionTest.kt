package dev.ide.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.use
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgressIndicator

/**
 * The indeterminate indicators under a zero motion duration scale, which is what iOS reports for
 * Reduce Motion and Android for "Animator duration scale: off".
 *
 * Rendered off screen at fixed frame times, so "is it still moving?" is a pixel comparison between two
 * frames rather than something only a device can answer. Each case renders Material's indicator beside
 * ours: that is the whole claim of [ProgressIndicators] — identical while motion is on, still moving when
 * it is off.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ProgressIndicatorMotionTest {

    @Test
    fun materialsCircularIndicatorFreezesWhenMotionIsOff() {
        val frames = render(CircularSize, CircularSize, scale = 0f) { MaterialCircularProgressIndicator() }
        assertTrue(
            frames[1].contentEquals(frames[2]),
            "Material's spinner parks on one frame with motion off, which is the bug being fixed here; " +
                "if this ever fails, the fallback in ProgressIndicators.kt has stopped being needed",
        )
    }

    @Test
    fun ourCircularIndicatorKeepsTurningWhenMotionIsOff() {
        val frames = render(CircularSize, CircularSize, scale = 0f) { CircularProgressIndicator() }
        assertFalse(frames[1].contentEquals(frames[2]), "the arc must have moved between two frames")
    }

    @Test
    fun ourLinearIndicatorKeepsMovingWhenMotionIsOff() {
        val frames = render(LinearWidth, LinearHeight, scale = 0f) { LinearProgressIndicator() }
        assertFalse(frames[1].contentEquals(frames[2]), "the bar must have moved between two frames")
    }

    /**
     * With motion on, both are handed straight to Material and must still animate.
     *
     * This does not compare them pixel for pixel to Material's own output: two scenes rendering the same
     * infinite transition at the same frame times do not agree frame for frame, because the transition
     * takes its start from when its coroutine first ran rather than from the time the scene was given.
     * That the motion-on path IS Material's is a single delegating call in [ProgressIndicators].
     */
    @Test
    fun withMotionOnTheIndicatorsStillAnimate() {
        val circular = render(CircularSize, CircularSize, scale = 1f) { CircularProgressIndicator() }
        assertFalse(circular.first().contentEquals(circular.last()), "the arc must have moved")

        val linear = render(LinearWidth, LinearHeight, scale = 1f) { LinearProgressIndicator() }
        assertFalse(linear.first().contentEquals(linear.last()), "the bar must have moved")
    }

    /**
     * [content] rendered at each of [FrameTimes] under a fixed motion duration [scale], as PNG bytes.
     *
     * The first frame is the one that starts the animation, so comparisons use the two after it.
     */
    private fun render(width: Int, height: Int, scale: Float, content: @Composable () -> Unit): List<ByteArray> =
        ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined + FixedMotionDurationScale(scale),
            content = content,
        ).use { scene -> FrameTimes.map { scene.render(it).png() } }

    private fun Image.png(): ByteArray = encodeToData(EncodedImageFormat.PNG)!!.bytes

    private class FixedMotionDurationScale(override val scaleFactor: Float) : MotionDurationScale

    private companion object {
        /** The indicators' own default sizes, at density 1. */
        const val CircularSize = 40
        const val LinearWidth = 240
        const val LinearHeight = 4

        /** Far enough apart that any indicator moving at all has visibly moved. */
        val FrameTimes = listOf(0L, 300_000_000L, 700_000_000L)
    }
}
