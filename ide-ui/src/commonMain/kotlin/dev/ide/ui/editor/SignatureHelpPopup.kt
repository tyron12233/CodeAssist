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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
    SignatureLine(
        sig = sig,
        activeParameter = sig.activeParameter ?: help.activeParameter,
        active = true,
    )
}

@Composable
private fun SignatureLine(sig: UiSignature, activeParameter: Int, active: Boolean) {
    val base = if (active) Ca.colors.textPrimary else Ca.colors.textSecondary
    val text = buildAnnotatedString {
        val active2 = sig.parameters.getOrNull(activeParameter)
        var cursor = 0
        if (active && active2 != null && active2.start in 0..sig.label.length && active2.end in active2.start..sig.label.length) {
            append(sig.label.substring(0, active2.start))
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Ca.colors.accent, fontWeight = FontWeight.Bold))
            append(sig.label.substring(active2.start, active2.end))
            pop()
            cursor = active2.end
        }
        append(sig.label.substring(cursor))
    }
    Text(text = text, style = Ca.type.codeSmall, color = base)
}

private const val MAX_SIGNATURES = 10
