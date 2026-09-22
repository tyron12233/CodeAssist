package dev.ide.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ide.ui.expandedCaretTurn
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An Arabic UI lays out right-to-left, so an icon that points the way the text runs has to point the
 * other way. The two icon vocabularies get there differently and both are guarded here: a [CaIcons]
 * vector carries Compose's `autoMirror` flag, while a [CaSymbols] glyph is text, which no layout
 * direction will flip, so it is swapped for its counterpart codepoint before it is drawn.
 */
class RtlIconMirroringTest {

    /** Icons whose arrow means "onward through the UI", which is the direction the reader is reading. */
    private val mirroring = mapOf(
        "chevronRight" to CaIcons.chevronRight,
        "chevronLeft" to CaIcons.chevronLeft,
        "caretRight" to CaIcons.caretRight,
        "arrowRight" to CaIcons.arrowRight,
        "undo" to CaIcons.undo,
        "redo" to CaIcons.redo,
        "sidebar" to CaIcons.sidebar,
        "panelRight" to CaIcons.panelRight,
    )

    /**
     * Icons that keep their drawn orientation everywhere: vertical ones, symmetric ones, and the ones
     * whose direction means something other than reading order — playback runs one way, and a VCS graph
     * is read as a diagram rather than as text.
     */
    private val fixed = mapOf(
        "chevronDown" to CaIcons.chevronDown,
        "chevronUp" to CaIcons.chevronUp,
        "caretDown" to CaIcons.caretDown,
        "play" to CaIcons.play,
        "stop" to CaIcons.stop,
        "gitBranch" to CaIcons.gitBranch,
        "gitMerge" to CaIcons.gitMerge,
        "gitPullRequest" to CaIcons.gitPullRequest,
        "terminal" to CaIcons.terminal,
        "split" to CaIcons.split,
        "share" to CaIcons.share,
        "search" to CaIcons.search,
    )

    @Test
    fun readingDirectionVectorsCarryAutoMirror() {
        for ((name, icon) in mirroring) {
            assertTrue(icon.autoMirror, "$name points along the reading direction, so it must auto-mirror")
        }
        for ((name, icon) in fixed) {
            assertFalse(icon.autoMirror, "$name does not track the reading direction and must not mirror")
        }
    }

    @Test
    fun onlyReadingDirectionGlyphsAreSwapped() {
        assertEquals(CaSymbols.chevronLeft, CaSymbols.mirrored(CaSymbols.chevronRight))
        assertEquals(CaSymbols.chevronRight, CaSymbols.mirrored(CaSymbols.chevronLeft))
        assertEquals(CaSymbols.arrowForward, CaSymbols.mirrored(CaSymbols.arrowBack))
        assertEquals(CaSymbols.arrowBack, CaSymbols.mirrored(CaSymbols.arrowForward))
        // Direction that is not reading order, and the vertical carets, stay put.
        val fixedGlyphs = listOf(CaSymbols.playArrow, CaSymbols.trendingUp, CaSymbols.arrowDropDown, CaSymbols.arrowDropUp)
        for (glyph in fixedGlyphs) {
            assertEquals(glyph, CaSymbols.mirrored(glyph))
        }
        // Mirroring twice is the identity, so an LtrContent island inside an RTL screen is not a one-way door.
        for (glyph in fixedGlyphs + listOf(CaSymbols.chevronRight, CaSymbols.arrowBack, CaSymbols.check)) {
            assertEquals(glyph, CaSymbols.mirrored(CaSymbols.mirrored(glyph)))
        }
    }

    @Test
    fun aVectorChevronIsDrawnTheOtherWayRoundUnderRtl() {
        val pointingOnward = render(LayoutDirection.Ltr, CaIcons.chevronRight)
        val pointingBack = render(LayoutDirection.Ltr, CaIcons.chevronLeft)
        assertDiffers("the two chevrons", pointingOnward, pointingBack)

        assertMatches("chevron_right under RTL", render(LayoutDirection.Rtl, CaIcons.chevronRight), pointingBack)
        assertMatches("chevron_left under RTL", render(LayoutDirection.Rtl, CaIcons.chevronLeft), pointingOnward)
    }

    @Test
    fun aVerticalVectorIsUntouchedUnderRtl() {
        assertMatches(
            "chevron_down under RTL",
            render(LayoutDirection.Rtl, CaIcons.chevronDown),
            render(LayoutDirection.Ltr, CaIcons.chevronDown),
        )
    }

    @Test
    fun aGlyphChevronIsSwappedUnderRtl() {
        val pointingOnward = render(LayoutDirection.Ltr, CaSymbols.chevronRight)
        val pointingBack = render(LayoutDirection.Ltr, CaSymbols.chevronLeft)
        // Also guards the assertion below from passing on two identical missing-glyph boxes, which is what
        // the scene would draw if the subset font failed to resolve.
        assertDiffers("the two chevron glyphs", pointingOnward, pointingBack)

        assertMatches("chevron_right under RTL", render(LayoutDirection.Rtl, CaSymbols.chevronRight), pointingBack)
        assertMatches("chevron_left under RTL", render(LayoutDirection.Rtl, CaSymbols.chevronLeft), pointingOnward)
    }

    @Test
    fun theExpandedDisclosureCaretPointsDownInBothDirections() {
        val ltr = alpha(LayoutDirection.Ltr) { Caret(expandedCaretTurn()) }
        val rtl = alpha(LayoutDirection.Rtl) { Caret(expandedCaretTurn()) }
        assertMatches("the expanded file-tree caret", rtl, ltr)
        // The mirrored caret has to turn the other way: the LTR quarter turn leaves it pointing up.
        assertDiffers("turning the RTL caret clockwise", alpha(LayoutDirection.Rtl) { Caret(90f) }, ltr)
    }

    @Test
    fun aPlayGlyphIsUntouchedUnderRtl() {
        assertMatches(
            "play_arrow under RTL",
            render(LayoutDirection.Rtl, CaSymbols.playArrow),
            render(LayoutDirection.Ltr, CaSymbols.playArrow),
        )
    }

    @Composable
    private fun Caret(turn: Float) =
        Icon(CaIcons.caretRight, null, Modifier.fillMaxSize().rotate(turn), tint = Color.White)

    private fun render(direction: LayoutDirection, icon: ImageVector): IntArray =
        alpha(direction) { Icon(icon, null, Modifier.fillMaxSize(), tint = Color.White) }

    private fun render(direction: LayoutDirection, glyph: Char): IntArray =
        alpha(direction) { Symbol(glyph, null, size = CanvasDp, tint = Color.White) }

    /**
     * The alpha of every pixel the composable painted, row-major.
     *
     * Two renders are compared by mean alpha difference rather than for exact equality: Skia rasterizes a
     * path drawn under a negative scale slightly differently from the same path drawn already mirrored,
     * and those sub-pixel differences are not what this is asserting.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun alpha(direction: LayoutDirection, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = CanvasPx, height = CanvasPx, density = Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                CodeAssistTheme(dark = true) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
                }
            }
        }
        try {
            scene.render() // the subset fonts resolve asynchronously, so frame 1 can still be a fallback
            val bitmap = Bitmap.makeFromImage(scene.render(16_000_000L))
            val out = IntArray(CanvasPx * CanvasPx)
            for (y in 0 until CanvasPx) {
                for (x in 0 until CanvasPx) {
                    out[y * CanvasPx + x] = (bitmap.getColor(x, y) ushr 24) and 0xFF
                }
            }
            assertTrue(out.any { it != 0 }, "nothing was painted")
            return out
        } finally {
            scene.close()
        }
    }

    private fun meanDiff(a: IntArray, b: IntArray): Double =
        a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size

    private fun assertMatches(what: String, actual: IntArray, expected: IntArray) {
        val diff = meanDiff(actual, expected)
        assertTrue(diff < SameTolerance, "$what should render as the other form, but differs by $diff")
    }

    private fun assertDiffers(what: String, a: IntArray, b: IntArray) {
        val diff = meanDiff(a, b)
        assertTrue(diff > SameTolerance * 4, "$what render identically (differ by $diff), so nothing is under test")
    }

    private companion object {
        const val CanvasPx = 64
        val CanvasDp = 64.dp

        /** Mean per-pixel alpha difference, out of 255, still counted as the same drawing. */
        const val SameTolerance = 1.5
    }
}
