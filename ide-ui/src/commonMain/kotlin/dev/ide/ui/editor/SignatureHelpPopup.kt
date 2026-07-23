package dev.ide.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiSignature
import dev.ide.ui.backend.UiSignatureHelp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.sig_more
import dev.ide.ui.generated.resources.sig_peek_collapse
import dev.ide.ui.generated.resources.sig_peek_tip
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The parameter-info panel (IntelliJ-style), floated above the call. Each applicable overload is one line; the
 * active overload is shown in full color with the parameter the caret currently sits on bolded + accented,
 * while the other overloads are dimmed. It is purely informational — non-focusable, never steals keystrokes
 * from the editor (the host dismisses it on Esc / when the caret leaves the call).
 *
 * On [mobile] the panel adapts so a many-parameter call (e.g. a Compose `Text`) can't swallow the screen: its
 * height is capped to a fraction of the window (overflow scrolls), its width to the screen width, and instead of
 * stacking every overload it shows ONE overload at a time with a `‹ i/n ›` stepper. The compact line elides
 * parameters that don't fit, so a **long-press peek** (a hint invites it) expands the shown overload to its whole
 * definition — full signature + documentation — with a second long-press (or a tap on the hint) collapsing it.
 */
@Composable
fun SignatureHelpPopup(help: UiSignatureHelp, mobile: Boolean = false) {
    if (help.signatures.isEmpty()) return
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val winH = with(density) { window.height.toDp() }
    val winW = with(density) { window.width.toDp() }

    // Mobile "peek": a long-press expands the compact (windowed) line into the whole definition + docs.
    var peek by remember { mutableStateOf(false) }
    val expanded = mobile && peek

    // Which overload the mobile single-line / peek view shows: it follows the backend's active overload, but a
    // manual step (‹ ›) sticks until the active overload changes again. Lifted here so the stepper and the peek
    // stay on the same overload when the user long-presses.
    var shown by remember { mutableStateOf(help.activeSignature) }
    var trackedActive by remember { mutableStateOf(help.activeSignature) }
    if (help.activeSignature != trackedActive) {
        trackedActive = help.activeSignature
        shown = help.activeSignature
    }
    val shownIndex = shown.coerceIn(0, help.signatures.lastIndex)

    // Cap the panel so it can never grow into a full-screen wall; the verticalScroll handles any overflow. A peek
    // gets more room since it carries the whole signature plus documentation.
    val maxH = when {
        expanded -> (winH * 0.6f).coerceAtLeast(160.dp)
        mobile -> (winH * 0.38f).coerceAtLeast(120.dp)
        else -> minOf(winH - 24.dp, 360.dp)
    }
    val maxW = if (mobile) (winW - 24.dp).coerceIn(120.dp, 560.dp) else 560.dp

    // Mobile gestures on the panel: a long-press anywhere toggles the peek; once expanded, a tap anywhere
    // collapses it (a tap that lands on the stepper arrows is consumed by them first, so it steps instead). Keyed
    // on [expanded] so the collapse-on-tap handler is armed only while peeking. It coexists with the column's
    // verticalScroll (hold/tap vs drag) and captures the stable `peek` state object.
    val gestures = if (mobile) Modifier.pointerInput(expanded) {
        detectTapGestures(
            onTap = if (expanded) ({ peek = false }) else null,
            onLongPress = { peek = !peek },
        )
    } else Modifier

    Column(
        Modifier
            .widthIn(min = 80.dp, max = maxW)
            .heightIn(max = maxH)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md))
            .then(gestures)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .verticalScroll(scroll),
    ) {
        when {
            expanded -> SignaturePeek(help, shownIndex, onStep = { shown = it }, onCollapse = { peek = false })
            mobile -> {
                MobileSignature(help, shownIndex, onStep = { shown = it })
                if (peekWorthwhile(help.signatures[shownIndex])) {
                    PeekHint(stringResource(Res.string.sig_peek_tip), onActivate = { peek = true })
                }
            }
            else -> DesktopSignatures(help)
        }
    }
}

/** Desktop / wide view: every applicable overload stacked, the active one marked with `▸` and its active
 *  parameter accented, capped at [MAX_SIGNATURES] so a heavily-overloaded call (println, valueOf, …) stays a
 *  panel, not a wall. */
@Composable
private fun DesktopSignatures(help: UiSignatureHelp) {
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
                windowed = false,
            )
        }
    }
    if (help.signatures.size > shown.size) {
        Text(
            stringResource(Res.string.sig_more, help.signatures.size - shown.size),
            style = Ca.type.codeSmall,
            color = Ca.colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Mobile compact view: the [shownIndex] overload as a single (windowed) line, with a `‹ i/n ›` stepper above it
 * when the call is overloaded.
 */
@Composable
private fun MobileSignature(help: UiSignatureHelp, shownIndex: Int, onStep: (Int) -> Unit) {
    if (help.signatures.size > 1) StepperRow(help, shownIndex, onStep)
    val sig = help.signatures[shownIndex]
    SignatureLine(
        sig = sig,
        activeParameter = sig.activeParameter ?: help.activeParameter,
        active = true,
        windowed = true,
    )
}

/**
 * Mobile peek (long-press) view: the [shownIndex] overload rendered in FULL — the whole signature (no windowing,
 * wrapping across lines) plus its documentation when the backend supplies any — so a truncated call can be read
 * in its entirety. The stepper stays available to browse overloads; a `Long-press to collapse` hint (also
 * tappable) returns to the compact line.
 */
@Composable
private fun SignaturePeek(help: UiSignatureHelp, shownIndex: Int, onStep: (Int) -> Unit, onCollapse: () -> Unit) {
    if (help.signatures.size > 1) StepperRow(help, shownIndex, onStep)
    val sig = help.signatures[shownIndex]
    SignatureLine(
        sig = sig,
        activeParameter = sig.activeParameter ?: help.activeParameter,
        active = true,
        windowed = false,
    )
    val doc = sig.documentation?.trim()?.takeIf { it.isNotEmpty() }
    if (doc != null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Ca.colors.separator))
        Text(doc, style = Ca.type.footnote, color = Ca.colors.textSecondary)
    }
    PeekHint(stringResource(Res.string.sig_peek_collapse), onActivate = onCollapse)
}

/** The `‹ i/n ›` overload stepper. [onStep] receives the new index; the arrows disable at the ends. */
@Composable
private fun StepperRow(help: UiSignatureHelp, shownIndex: Int, onStep: (Int) -> Unit) {
    val last = help.signatures.lastIndex
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "‹",
            style = Ca.type.codeSmall,
            color = if (shownIndex > 0) Ca.colors.accent else Ca.colors.textTertiary,
            modifier = Modifier.clickable(enabled = shownIndex > 0) { onStep(shownIndex - 1) }.padding(horizontal = 2.dp),
        )
        Text(
            text = "${shownIndex + 1}/${help.signatures.size}",
            style = Ca.type.codeSmall,
            color = Ca.colors.textSecondary,
        )
        Text(
            text = "›",
            style = Ca.type.codeSmall,
            color = if (shownIndex < last) Ca.colors.accent else Ca.colors.textTertiary,
            modifier = Modifier.clickable(enabled = shownIndex < last) { onStep(shownIndex + 1) }.padding(horizontal = 2.dp),
        )
    }
}

/** A muted hint that doubles as a button: tap OR long-press runs [onActivate] (peek / collapse). A small caption
 *  so it reads as an aside, not a line of the signature. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeekHint(text: String, onActivate: () -> Unit) {
    Text(
        text = text,
        style = Ca.type.caption2,
        color = Ca.colors.textTertiary,
        modifier = Modifier
            .padding(top = 4.dp)
            .combinedClickable(onClick = onActivate, onLongClick = onActivate),
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
    // Style one parameter span: the active one is accented + bold; one a named argument has already supplied is
    // dimmed to [dim] (it no longer needs typing); the rest inherit the line colour. Active wins over dimmed.
    fun param(i: Int, activeIdx: Int) {
        val p = params[i]
        val style = when {
            active && i == activeIdx -> SpanStyle(color = accent, fontWeight = FontWeight.Bold)
            p.alreadyNamed -> SpanStyle(color = dim)
            else -> null
        }
        style?.let { pushStyle(it) }
        append(sig.label.substring(p.start, p.end))
        if (style != null) pop()
    }

    when {
        doWindow -> {
            val last = params.lastIndex
            val center = windowCenter(activeParameter, last)
            val lo = (center - SIGNATURE_WINDOW_RADIUS).coerceAtLeast(0)
            val hi = (center + SIGNATURE_WINDOW_RADIUS).coerceAtMost(last)
            append(sig.label.substring(0, params[0].start))                       // call prefix, e.g. "Text("
            if (lo > 0) { ellipsis(); append(sig.label.substring(params[lo - 1].end, params[lo].start)) } // "…, "
            for (i in lo..hi) {
                param(i, center)
                if (i < hi) append(sig.label.substring(params[i].end, params[i + 1].start))   // real ", " separator
            }
            if (hi < last) { append(sig.label.substring(params[hi].end, params[hi + 1].start)); ellipsis() } // ", …"
            append(sig.label.substring(params[last].end))                         // suffix, e.g. ")" / "): Unit"
        }
        rangesValid -> {
            // Full signature (desktop, the peek, or a short call): walk every parameter so the active one is
            // accented and any already-named one is dimmed, keeping the literal separators between them.
            append(sig.label.substring(0, params[0].start))
            for (i in params.indices) {
                param(i, activeParameter)
                val nextStart = if (i < params.lastIndex) params[i + 1].start else sig.label.length
                append(sig.label.substring(params[i].end, nextStart))
            }
        }
        else -> {
            // Degenerate: parameter ranges couldn't be located — render the label, bolding the active span if we can.
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
}

/** Whether a peek would reveal more than the compact mobile line already shows: a windowed (parameter-eliding)
 *  signature, or attached documentation. Drives the discovery hint so it appears only when it helps. */
internal fun peekWorthwhile(sig: UiSignature): Boolean =
    !sig.documentation.isNullOrBlank() || signatureWouldWindow(sig)

/** Mirror of [signatureAnnotated]'s windowing decision (with `windowed = true`): true when the compact mobile
 *  line would elide parameters, i.e. the peek shows strictly more of the signature. */
internal fun signatureWouldWindow(sig: UiSignature): Boolean {
    val params = sig.parameters
    val rangesValid = params.isNotEmpty() &&
        params.all { it.start in 0..sig.label.length && it.end in it.start..sig.label.length }
    return rangesValid && params.size > SIGNATURE_WINDOW_THRESHOLD
}

/** Clamp the active-parameter index to a valid window centre (a trailing/vararg caret centres on the last param). */
private fun windowCenter(activeParameter: Int, lastIndex: Int): Int = activeParameter.coerceIn(0, lastIndex)

private const val MAX_SIGNATURES = 10
/** Params shown either side of the active one when windowing a small-screen signature. */
private const val SIGNATURE_WINDOW_RADIUS = 1
/** Only window calls with more than this many parameters (short calls show in full even on mobile). */
private const val SIGNATURE_WINDOW_THRESHOLD = 5
