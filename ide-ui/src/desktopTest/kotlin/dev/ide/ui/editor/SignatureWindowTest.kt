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
}
