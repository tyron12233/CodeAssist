package dev.ide.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.settings_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Bitmap
import java.util.Locale
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The UI ships its own Arabic face because no platform sans has one: an Arabic screen would otherwise be
 * drawn by whatever each device happens to fall back to.
 *
 * What is worth pinning down is the seam rather than the drawing. The face is chosen from the language,
 * and the strings are chosen by Compose Resources from its own reading of the locale, so the two have to
 * agree or the app renders Arabic text in a Latin-only face (or the reverse).
 */
class ArabicUiFontTest {

    private val original: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun anArabicUiGetsTheBundledFaceAndEveryOtherKeepsThePlatformSans() {
        Locale.setDefault(Locale.forLanguageTag("ar"))
        assertEquals(capture { rememberIbmPlexSansArabic() }, capture { rememberUiFont() })

        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals(capture { rememberIbmPlexSansArabic() }, capture { rememberUiFont() }, "a regional Arabic too")

        for (tag in listOf("en", "en-US", "es", "ru", "zh", "pt-BR", "in")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals(FontFamily.SansSerif, capture { rememberUiFont() }, "$tag keeps the platform sans")
        }
    }

    /**
     * The face and the strings are picked by two different readings of the locale. If they ever diverge,
     * the UI draws one language's text in the other language's font, so they are asserted together.
     */
    @Test
    fun theFaceAndTheStringsAgreeOnWhichLanguageIsShowing() {
        Locale.setDefault(Locale.forLanguageTag("ar"))
        assertEquals("الإعدادات", capture { stringResource(Res.string.settings_title) })
        assertNotEquals(FontFamily.SansSerif, capture { rememberUiFont() })

        Locale.setDefault(Locale.forLanguageTag("en"))
        assertEquals("Settings", capture { stringResource(Res.string.settings_title) })
        assertEquals(FontFamily.SansSerif, capture { rememberUiFont() })
    }

    @Test
    fun theBundledFaceReallyDrawsTheArabicRatherThanFallingBack() {
        val bundled = draw(FontWeight.Normal) { rememberIbmPlexSansArabic() }
        val fallback = draw(FontWeight.Normal) { FontFamily.SansSerif }
        assertDiffers("the bundled face and the platform fallback", bundled, fallback)
    }

    /**
     * The type scale sets display titles in [FontWeight.Light] and body in [FontWeight.Normal]. A family
     * missing a weight does not fail: Compose rounds to the nearest one it has, so a dropped file shows
     * up only as titles that quietly stopped being light.
     */
    @Test
    fun everyWeightTheTypeScaleAsksForIsItsOwnFile() {
        val weights = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
        val drawn = weights.map { it to draw(it) { rememberIbmPlexSansArabic() } }
        for ((i, a) in drawn.withIndex()) {
            for (b in drawn.drop(i + 1)) {
                assertDiffers("${a.first.weight} and ${b.first.weight}", a.second, b.second)
            }
        }
    }

    /** Reads one value out of a composition, which is the only place a `remember`ing function runs. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun <T> capture(value: @Composable () -> T): T {
        var captured: T? = null
        val scene = ImageComposeScene(width = 1, height = 1) { captured = value() }
        try {
            scene.render()
            scene.render(16_000_000L) // resource-backed values resolve asynchronously
        } finally {
            scene.close()
        }
        return captured ?: error("nothing was captured")
    }

    /** The alpha of every pixel of one Arabic word set in [family] at [weight]. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun draw(weight: FontWeight, family: @Composable () -> FontFamily): IntArray {
        val scene = ImageComposeScene(width = CanvasPx, height = CanvasPx, density = Density(1f)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("الإعدادات", fontFamily = family(), fontWeight = weight, fontSize = 22.sp, color = Color.White)
            }
        }
        try {
            scene.render() // frame 1 can still be drawing with a fallback while the font loads
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

    private fun assertDiffers(what: String, a: IntArray, b: IntArray) {
        val diff = a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size
        assertTrue(diff > 1.0, "$what render the same text identically (differ by $diff)")
    }

    private companion object {
        const val CanvasPx = 160
    }
}
