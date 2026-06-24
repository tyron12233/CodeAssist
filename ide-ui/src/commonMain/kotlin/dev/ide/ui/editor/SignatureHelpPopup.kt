package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiSignature
import dev.ide.ui.backend.UiSignatureHelp
import dev.ide.ui.theme.Ca

/**
 * The parameter-info panel (IntelliJ-style), floated above the call. Each applicable overload is one line; the
 * active overload is shown in full color with the parameter the caret currently sits on bolded + accented,
 * while the other overloads are dimmed. It is purely informational — non-focusable, never steals keystrokes
 * from the editor (the host dismisses it on Esc / when the caret leaves the call).
 *
 * On [mobile] the panel adapts so a many-parameter call (e.g. a Compose `Text`) can't swallow the screen: its
 * height is capped to a fraction of the window (overflow scrolls), its width to the screen width, and instead of
 * stacking every overload it shows ONE overload at a time with a `‹ i/n ›` stepper.
 */
@Composable
fun SignatureHelpPopup(help: UiSignatureHelp, mobile: Boolean = false) {
    if (help.signatures.isEmpty()) return
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val winH = with(density) { window.height.toDp() }
    val winW = with(density) { window.width.toDp() }
    // Cap the panel so it can never grow into a full-screen wall; the verticalScroll handles any overflow.
    val maxH = if (mobile) (winH * 0.38f).coerceAtLeast(120.dp) else minOf(winH - 24.dp, 360.dp)
    val maxW = if (mobile) (winW - 24.dp).coerceIn(120.dp, 560.dp) else 560.dp

    Column(
        Modifier
            .widthIn(min = 80.dp, max = maxW)
            .heightIn(max = maxH)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md))
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .verticalScroll(scroll),
    ) {
        if (mobile && help.signatures.size > 1) {
            SteppedSignature(help)
        } else {
            // Cap the stacked overloads so a heavily-overloaded call (println, valueOf, …) stays a panel, not a wall.
            val shown = help.signatures.take(MAX_SIGNATURES)
            shown.forEachIndexed { index, sig ->
                Row(verticalAlignment = Alignment.Top) {
                    if (help.signatures.size > 1) {
                        Text(
                            text = if (index == help.activeSignature) "▸ " else "   ",
                            style = Ca.type.codeSmall,
                            color = Ca.colors.accent,
                        )
                    }
                    SignatureLine(
                        sig = sig,
                        activeParameter = sig.activeParameter ?: help.activeParameter,
                        active = index == help.activeSignature,
                        windowed = mobile,
                    )
                }
            }
            if (help.signatures.size > shown.size) {
                Text(
                    "+${help.signatures.size - shown.size} more",
                    style = Ca.type.codeSmall,
                    color = Ca.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Mobile single-overload view: shows one signature with a `‹ i/n ›` stepper. The shown index follows the
 * backend's active overload as the user types into a new argument, but a manual step sticks until the active
 * overload changes again.
 */
@Composable
private fun SteppedSignature(help: UiSignatureHelp) {
    val last = help.signatures.lastIndex
    var shown by remember { mutableStateOf(help.activeSignature) }
    var trackedActive by remember { mutableStateOf(help.activeSignature) }
    if (help.activeSignature != trackedActive) {
        trackedActive = help.activeSignature
        shown = help.activeSignature
    }
    val index = shown.coerceIn(0, last)
    val sig = help.signatures[index]

    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "‹",
            style = Ca.type.codeSmall,
            color = if (index > 0) Ca.colors.accent else Ca.colors.textTertiary,
            modifier = Modifier.clickable(enabled = index > 0) { shown = index - 1 }.padding(horizontal = 2.dp),
        )
        Text(
            text = "${index + 1}/${help.signatures.size}",
            style = Ca.type.codeSmall,
            color = Ca.colors.textSecondary,
        )
        Text(
            text = "›",
            style = Ca.type.codeSmall,
            color = if (index < last) Ca.colors.accent else Ca.colors.textTertiary,
            modifier = Modifier.clickable(enabled = index < last) { shown = index + 1 }.padding(horizontal = 2.dp),
        )
    }
    // SteppedSignature only runs on mobile, so the line is always windowed here.
    SignatureLine(
        sig = sig,
        activeParameter = sig.activeParameter ?: help.activeParameter,
        active = true,
        windowed = true,
    )
}

@Composable
private fun SignatureLine(sig: UiSignature, activeParameter: Int, active: Boolean, windowed: Boolean = false) {
    val base = if (active) Ca.colors.textPrimary else Ca.colors.textSecondary
    val text = signatureAnnotated(sig, activeParameter, active, Ca.colors.accent, Ca.colors.textTertiary, windowed)
    Text(text = text, style = Ca.type.codeSmall, color = base)
}

/**
 * The rendered signature line: the active parameter bolded + [accent]-coloured against the full [sig.label].
 *
 * When [windowed] (small screens) and the call has more than [SIGNATURE_WINDOW_THRESHOLD] parameters, only a
 * window of [SIGNATURE_WINDOW_RADIUS] parameters either side of the active one is shown, with a [dim] `…`
 * standing in for the elided runs — so a Compose `Text` (≈20 params) reads as `Text(…, color, modifier, …)`
 * instead of swallowing the screen. The call prefix (`Text(`) and suffix (`)` / `): Unit`) are always kept.
 * Pure (non-composable) so it is unit-testable; colours are passed in.
 */
internal fun signatureAnnotated(
    sig: UiSignature,
    activeParameter: Int,
    active: Boolean,
    accent: Color,
    dim: Color,
    windowed: Boolean,
): AnnotatedString = buildAnnotatedString {
    val params = sig.parameters
    val rangesValid = params.isNotEmpty() &&
        params.all { it.start in 0..sig.label.length && it.end in it.start..sig.label.length }
    val doWindow = windowed && rangesValid && params.size > SIGNATURE_WINDOW_THRESHOLD

    fun ellipsis() { pushStyle(SpanStyle(color = dim)); append("…"); pop() }
    fun param(i: Int) {
        val p = params[i]
        val centerActive = active && i == windowCenter(activeParameter, params.lastIndex)
        if (centerActive) pushStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold))
        append(sig.label.substring(p.start, p.end))
        if (centerActive) pop()
    }

    if (doWindow) {
        val last = params.lastIndex
        val center = windowCenter(activeParameter, last)
        val lo = (center - SIGNATURE_WINDOW_RADIUS).coerceAtLeast(0)
        val hi = (center + SIGNATURE_WINDOW_RADIUS).coerceAtMost(last)
        append(sig.label.substring(0, params[0].start))                       // call prefix, e.g. "Text("
        if (lo > 0) { ellipsis(); append(sig.label.substring(params[lo - 1].end, params[lo].start)) } // "…, "
        for (i in lo..hi) {
            param(i)
            if (i < hi) append(sig.label.substring(params[i].end, params[i + 1].start))   // real ", " separator
        }
        if (hi < last) { append(sig.label.substring(params[hi].end, params[hi + 1].start)); ellipsis() } // ", …"
        append(sig.label.substring(params[last].end))                         // suffix, e.g. ")" / "): Unit"
    } else {
        // Full signature (desktop, or a short call): bold just the active parameter span.
        val active2 = params.getOrNull(activeParameter)
        var cursor = 0
        if (active && active2 != null && active2.start in 0..sig.label.length && active2.end in active2.start..sig.label.length) {
            append(sig.label.substring(0, active2.start))
            pushStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold))
            append(sig.label.substring(active2.start, active2.end))
            pop()
            cursor = active2.end
        }
        append(sig.label.substring(cursor))
    }
}

/** Clamp the active-parameter index to a valid window centre (a trailing/vararg caret centres on the last param). */
private fun windowCenter(activeParameter: Int, lastIndex: Int): Int = activeParameter.coerceIn(0, lastIndex)

private const val MAX_SIGNATURES = 10
/** Params shown either side of the active one when windowing a small-screen signature. */
private const val SIGNATURE_WINDOW_RADIUS = 1
/** Only window calls with more than this many parameters (short calls show in full even on mobile). */
private const val SIGNATURE_WINDOW_THRESHOLD = 5
