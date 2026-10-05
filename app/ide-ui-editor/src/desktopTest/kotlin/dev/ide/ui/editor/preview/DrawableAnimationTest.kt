package dev.ide.ui.editor.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.UiAnimatedValue
import dev.ide.ui.backend.UiAnimationTarget
import dev.ide.ui.backend.UiAnimator
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiFrame
import dev.ide.ui.backend.UiInterpolator
import dev.ide.ui.backend.UiKeyframe
import dev.ide.ui.backend.UiVectorGroup
import dev.ide.ui.backend.UiVectorPath
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Playback of `<animation-list>` and `<animated-vector>`: which frame and which property values show when. */
class DrawableAnimationTest {

    private val red = UiDrawable.SolidColor(0xFFFF0000L)
    private val green = UiDrawable.SolidColor(0xFF00FF00L)
    private val blue = UiDrawable.SolidColor(0xFF0000FFL)
    private val linear = UiInterpolator("linear")

    private fun frames(oneShot: Boolean) =
        UiDrawable.Frames(listOf(UiFrame(red, 100), UiFrame(green, 50), UiFrame(blue, 50)), oneShot)

    @Test
    fun animationListShowsEachFrameForItsDurationAndLoops() {
        val d = frames(oneShot = false)
        assertTrue(DrawableAnimation.isAnimated(d))
        assertNull(DrawableAnimation.durationMs(d), "a looping list never ends")
        assertEquals(red, DrawableAnimation.atTime(d, 0))
        assertEquals(red, DrawableAnimation.atTime(d, 99))
        assertEquals(green, DrawableAnimation.atTime(d, 100))
        assertEquals(blue, DrawableAnimation.atTime(d, 150))
        assertEquals(red, DrawableAnimation.atTime(d, 200), "the loop starts over")
    }

    @Test
    fun aOneShotListHoldsItsLastFrame() {
        val d = frames(oneShot = true)
        assertEquals(200L, DrawableAnimation.durationMs(d))
        assertEquals(blue, DrawableAnimation.atTime(d, 5_000))
    }

    private fun spinner(vararg targets: UiAnimationTarget) = UiDrawable.AnimatedVector(
        UiDrawable.Vector(
            widthDp = 24f, heightDp = 24f, viewportWidth = 24f, viewportHeight = 24f, rootAlpha = 1f, name = "root",
            nodes = listOf(
                UiVectorGroup(
                    name = "g", pivotX = 12f, pivotY = 12f,
                    children = listOf(
                        UiVectorPath(
                            pathData = "M0,0 L24,0 L24,24 L0,24 Z", name = "p",
                            fillColor = 0xFFFF0000L, strokeColor = null, strokeWidthVp = 0f, fillAlpha = 1f, strokeAlpha = 1f,
                        ),
                    ),
                ),
            ),
        ),
        targets.toList(),
    )

    private fun prop(
        property: String, from: UiAnimatedValue?, to: UiAnimatedValue, duration: Long,
        offset: Long = 0, repeat: Int = 0, reverse: Boolean = false, interpolator: UiInterpolator = linear,
    ) = UiAnimator.Property(
        property, listOf(UiKeyframe(0f, from, null), UiKeyframe(1f, to, null)), duration, offset, repeat, reverse, interpolator,
    )

    private fun n(v: Float) = UiAnimatedValue.Number(v)

    private fun group(d: UiDrawable, t: Long) =
        (DrawableAnimation.atTime(d, t) as UiDrawable.Vector).nodes.single() as UiVectorGroup

    private fun path(d: UiDrawable, t: Long) = group(d, t).children.single() as UiVectorPath

    @Test
    fun aRotationAnimatorTurnsTheGroupAndRepeatsForever() {
        val d = spinner(UiAnimationTarget("g", prop("rotation", n(0f), n(360f), 1000, repeat = -1)))
        assertNull(DrawableAnimation.durationMs(d))
        assertEquals(0f, group(d, 0).rotation)
        assertEquals(90f, group(d, 250).rotation, 0.01f)
        assertEquals(90f, group(d, 1250).rotation, 0.01f, "the second repeat runs the same way")
        assertIs<UiDrawable.AnimatedVector>(d) // the original stays untouched
        assertEquals(0f, (d.vector.nodes.single() as UiVectorGroup).rotation)
    }

    @Test
    fun aSequentialSetRunsItsAnimatorsOneAfterAnother() {
        val set = UiAnimator.Set(
            listOf(
                prop("trimPathEnd", n(0f), n(1f), 400),
                prop("fillAlpha", n(1f), n(0f), 200, offset = 100),
            ),
            sequential = true,
        )
        val d = spinner(UiAnimationTarget("p", set))
        assertEquals(700L, DrawableAnimation.durationMs(d))
        assertEquals(0.5f, path(d, 200).trimPathEnd, 0.001f)
        assertEquals(1f, path(d, 450).fillAlpha, "the second waits for the first and its own offset")
        assertEquals(0.5f, path(d, 600).fillAlpha, 0.001f)
        assertEquals(0f, path(d, 10_000).fillAlpha, "a finished animator holds its end value")
        assertEquals(1f, path(d, 10_000).trimPathEnd)
    }

    @Test
    fun aMissingValueFromStartsFromThePropertysOwnValueAndColorsBlend() {
        val d = spinner(UiAnimationTarget("p", prop("fillColor", null, UiAnimatedValue.Color(0xFF0000FFL), 100)))
        assertEquals(0xFFFF0000L, path(d, 0).fillColor)
        assertEquals(0xFF800080L, path(d, 50).fillColor)
        assertEquals(0xFF0000FFL, path(d, 100).fillColor)
    }

    @Test
    fun reverseRepeatsPlayBackwardsAndTheRootAlphaAnimates() {
        val d = spinner(UiAnimationTarget("root", prop("alpha", n(1f), n(0f), 100, repeat = 1, reverse = true)))
        assertEquals(200L, DrawableAnimation.durationMs(d))
        assertEquals(0.25f, (DrawableAnimation.atTime(d, 75) as UiDrawable.Vector).rootAlpha, 0.001f)
        assertEquals(0.75f, (DrawableAnimation.atTime(d, 175) as UiDrawable.Vector).rootAlpha, 0.001f)
    }

    @Test
    fun pathDataMorphsBetweenCompatibleShapes() {
        val to = UiAnimatedValue.PathData("M0,0 L12,0 L12,12 L0,12 Z")
        val d = spinner(UiAnimationTarget("p", prop("pathData", null, to, 100)))
        assertEquals("M0 0L18 0L18 18L0 18Z", path(d, 50).pathData)
        assertNull(PathMorph.lerp("M0,0 L1,1", "M0,0 C1,1 2,2 3,3", 0.5f), "different commands cannot morph")
        assertEquals("M0.5 -0.25", PathMorph.lerp("M0,0", "M1-0.5", 0.5f))
        assertEquals("M0.001 0", PathMorph.lerp("M1e-3,0", "M1e-3,0", 0.3f), "exponents are read as numbers")
    }

    @Test
    fun interpolatorsFollowTheFrameworkCurves() {
        val half = 0.5f
        assertEquals(0.5f, DrawableAnimation.interpolate(UiInterpolator("accelerate_decelerate"), half), 1e-4f)
        assertEquals(0.25f, DrawableAnimation.interpolate(UiInterpolator("accelerate"), half), 1e-4f)
        assertEquals(0.75f, DrawableAnimation.interpolate(UiInterpolator("decelerate"), half), 1e-4f)
        val fastOutSlowIn = UiInterpolator("cubic", points = listOf(0.4f, 0f, 0.2f, 1f))
        assertEquals(0.7756f, DrawableAnimation.interpolate(fastOutSlowIn, half), 2e-3f)
        assertEquals(1f, DrawableAnimation.interpolate(fastOutSlowIn, 1f))
        assertTrue(DrawableAnimation.interpolate(UiInterpolator("overshoot", factor = 2f), 0.8f) > 1f)
        val diagonal = UiInterpolator("path", pathData = "M0,0 L1,1")
        assertEquals(0.3f, DrawableAnimation.interpolate(diagonal, 0.3f), 0.01f)
    }

    @Test
    fun aStaticDrawableIsNotAnimated() {
        assertFalse(DrawableAnimation.isAnimated(red))
        assertEquals(0L, DrawableAnimation.durationMs(red))
        assertFalse(DrawableAnimation.isAnimated(UiDrawable.Frames(listOf(UiFrame(red, 100)), oneShot = false)))
    }

    /** A trimmed-away path draws nothing; the untrimmed path fills the canvas. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun trimPathEndHidesThePath() {
        fun centre(trimEnd: Float): Int {
            val v = (spinner().vector).let { vec ->
                vec.copy(nodes = listOf(UiVectorPath(
                    pathData = "M0,12 L24,12", fillColor = null, strokeColor = 0xFFFF0000L, strokeWidthVp = 24f,
                    fillAlpha = 1f, strokeAlpha = 1f, trimPathEnd = trimEnd,
                )))
            }
            val scene = ImageComposeScene(width = 48, height = 48, density = Density(2f)) {
                Image(painter = UiDrawablePainter(v, density = 2f), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            return try {
                scene.render()
                val png = scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                ImageIO.read(ByteArrayInputStream(png)).getRGB(36, 24)
            } finally {
                scene.close()
            }
        }
        assertEquals(0xFF, (centre(1f) shr 16) and 0xFF, "the full stroke covers the right half")
        assertEquals(0, (centre(0.5f) shr 24) and 0xFF, "trimmed to the left half, the right half stays clear")
    }
}
