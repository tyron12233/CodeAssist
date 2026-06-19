package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 */
@Composable
fun SignatureHelpPopup(help: UiSignatureHelp) {
    if (help.signatures.isEmpty()) return
    val scroll = rememberScrollState()
    Column(
        Modifier
            .widthIn(min = 80.dp, max = 560.dp)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md))
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .verticalScroll(scroll),
    ) {
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
