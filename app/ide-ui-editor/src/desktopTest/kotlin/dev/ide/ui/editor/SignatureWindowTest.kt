package dev.ide.ui.editor

import androidx.compose.ui.graphics.Color
import dev.ide.ui.backend.UiSignature
import dev.ide.ui.backend.UiSignatureParam
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the small-screen windowing of [signatureAnnotated]: a many-parameter call collapses to the active
 * parameter plus its immediate neighbours with `…` for the elided runs, while short calls and the desktop
 * (un-windowed) path render the full signature.
 */
class SignatureWindowTest {

    /** Build a `fn(p0, p1, …)` signature with correct `start`/`end` offsets for [n] params named p0..p(n-1). */
    private fun sig(n: Int, prefix: String = "fn(", suffix: String = ")"): UiSignature {
        val sb = StringBuilder(prefix)
        val params = ArrayList<UiSignatureParam>()
        for (i in 0 until n) {
            if (i > 0) sb.append(", ")
            val start = sb.length
            sb.append("p$i")
            params.add(UiSignatureParam("p$i", start, sb.length))
        }
        sb.append(suffix)
        return UiSignature(label = sb.toString(), parameters = params)
    }

    private fun rendered(sig: UiSignature, active: Int, windowed: Boolean): String =
        signatureAnnotated(sig, active, active = true, accent = Color.Red, dim = Color.Gray, windowed = windowed).text

    @Test
    fun windowsAroundActiveInTheMiddle() {
        // 8 params, active = 3 → only p2, p3, p4 shown, with leading + trailing ellipsis.
        assertEquals("fn(…, p2, p3, p4, …)", rendered(sig(8), active = 3, windowed = true))
    }

    @Test
    fun windowAtStartHasNoLeadingEllipsis() {
        assertEquals("fn(p0, p1, …)", rendered(sig(8), active = 0, windowed = true))
    }

    @Test
    fun windowAtEndHasNoTrailingEllipsis() {
        assertEquals("fn(…, p6, p7)", rendered(sig(8), active = 7, windowed = true))
    }

    @Test
    fun trailingActiveIndexClampsToLastParam() {
        // A vararg / past-the-end caret (active beyond the last index) centres on the last param.
        assertEquals("fn(…, p6, p7)", rendered(sig(8), active = 12, windowed = true))
    }

    @Test
    fun shortCallIsNotWindowedEvenOnSmallScreens() {
        // 4 params is at/under the threshold → full signature, no ellipsis.
        val s = sig(4)
        assertEquals(s.label, rendered(s, active = 1, windowed = true))
    }

    @Test
    fun desktopPathShowsTheFullSignature() {
        val s = sig(8)
        assertEquals(s.label, rendered(s, active = 3, windowed = false))
    }

    @Test
    fun keepsReturnTypeSuffixWhenWindowing() {
        val s = sig(8, suffix = "): Unit")
        assertEquals("fn(…, p2, p3, p4, …): Unit", rendered(s, active = 3, windowed = true))
    }

    // The peek (long-press) hint should surface exactly when a peek reveals more than the compact line.

    @Test
    fun peekWorthwhileWhenSignatureWindows() {
        assertEquals(true, signatureWouldWindow(sig(8)))
        assertEquals(true, peekWorthwhile(sig(8)))
    }

    @Test
    fun shortCallWithoutDocsIsNotWorthPeeking() {
        val s = sig(3)
        assertEquals(false, signatureWouldWindow(s))
        assertEquals(false, peekWorthwhile(s))
    }

    @Test
    fun documentationAloneMakesAShortCallWorthPeeking() {
        val s = sig(3).copy(documentation = "Returns the value.")
        assertEquals(false, signatureWouldWindow(s))
        assertEquals(true, peekWorthwhile(s))
    }

    @Test
    fun alreadyNamedParameterRendersDimmed() {
        // p1 has been supplied by a named argument → its span is the dim colour; the active p0 is accent, p2 plain.
        val base = sig(3)
        val withNamed = base.copy(
            parameters = base.parameters.mapIndexed { i, p -> if (i == 1) p.copy(alreadyNamed = true) else p },
        )
        val ann = signatureAnnotated(withNamed, activeParameter = 0, active = true, accent = Color.Red, dim = Color.Gray, windowed = false)
        val p1 = withNamed.parameters[1]
        val dimSpans = ann.spanStyles.filter { it.item.color == Color.Gray }
        assertEquals(1, dimSpans.size, "exactly one dim span, over the named parameter")
        assertEquals(p1.start, dimSpans[0].start)
        assertEquals(p1.end, dimSpans[0].end)
    }
}
