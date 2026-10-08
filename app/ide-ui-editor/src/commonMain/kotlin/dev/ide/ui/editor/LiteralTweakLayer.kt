package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.ColorPickerDialog
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.literal_tweak_decrease
import dev.ide.ui.generated.resources.literal_tweak_increase
import dev.ide.ui.generated.resources.literal_tweak_pick_color
import dev.ide.ui.generated.resources.literal_tweak_scrub
import dev.ide.ui.generated.resources.literal_tweak_toggle
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Horizontal drag distance per scrub step. */
private val SCRUB_STEP = 6.dp

/**
 * The literal-tweak chip: with the caret on a number, `Color(0x…)` or boolean literal in a file that has
 * `@Preview` composables, a small control floats above the literal: −/+ and a drag-to-scrub handle for a number,
 * a swatch that opens the color picker for a color, a switch for a boolean. Each change rewrites only the
 * literal's text, so the open preview takes it as a live-literal edit and follows at once.
 *
 * A scrub is one undo step (the drag runs inside a session batch). The color picker is hosted OUTSIDE [visible]'s
 * gate, because opening it takes focus from the editor, which would otherwise hide the chip and the dialog with it.
 */
@Composable
internal fun LiteralTweakLayer(
    session: EditorSession,
    visible: Boolean,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    lineHeightPx: Float,
    gutterWidthPx: Float,
    liftPx: Int,
) {
    // The literal a color pick will rewrite, captured with its source text so a pick after an unrelated edit to
    // that range is dropped instead of overwriting something else.
    var picking by remember(session) { mutableStateOf<Pair<TweakableLiteral.ColorHex, String>?>(null) }
    picking?.let { (lit, original) ->
        ColorPickerDialog(
            visible = true,
            initial = lit.argb,
            onDismiss = { picking = null },
            allowAlpha = true,
        ) { argb ->
            picking = null
            if (lit.end <= session.doc.length && session.doc.substring(lit.start, lit.end) == original) {
                replaceLiteral(session, lit.start, lit.end, lit.format(argb))
            }
        }
    }

    var dragging by remember(session) { mutableStateOf(false) }
    val selection = session.selection
    val revision = session.textRevision
    val literal = remember(session, revision, selection) {
        if (selection.collapsed) tweakableLiteralAt(session.doc.chars, selection.start) else null
    }
    // While a scrub is in flight keep the last literal up, even if a transient value (crossing zero) is briefly
    // not recognized; the drag itself tracks the range it is rewriting.
    // A plain holder, not state: it only remembers across the recompositions the literal/drag changes cause.
    val shown = remember(session) { arrayOfNulls<TweakableLiteral>(1) }
    if (literal != null || !dragging) shown[0] = literal
    val current = shown[0]
    // Hidden while the picker is open, so the chip does not show through the dialog's scrim.
    if (current == null || !(visible || dragging) || picking != null || session.previewMarkers.isEmpty()) return

    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    // In the pane, not a `Popup` (see [aboveLineInPane]): it stays up for as long as the caret is on the literal.
    Row(
        Modifier
            .aboveLineInPane(
                anchor = { caretGeometry(current.start).let { (_, x, top) -> x to top } },
                lineHeightPx = lineHeightPx,
                gapPx = gapPx,
                minX = gutterWidthPx.roundToInt(),
                liftPx = liftPx,
            )
            .clip(RoundedCornerShape(Ca.radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.sm))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when (current) {
            is TweakableLiteral.Number -> NumberControls(session, current, onDragging = { dragging = it })
            is TweakableLiteral.ColorHex -> {
                val description = stringResource(Res.string.literal_tweak_pick_color)
                Box(
                    Modifier
                        .padding(2.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(Ca.radius.control))
                        .background(Color(current.argb))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
                        .clickable { picking = current to session.doc.substring(current.start, current.end) }
                        .semantics { contentDescription = description },
                )
            }
            is TweakableLiteral.Bool -> {
                val description = stringResource(Res.string.literal_tweak_toggle)
                // A Material switch scaled to chip size: scale() alone keeps the full layout footprint, so the
                // switch is laid out at its natural size inside a box of the scaled size.
                Box(Modifier.size(38.dp, 24.dp), contentAlignment = Alignment.Center) {
                    Switch(
                        checked = current.value,
                        onCheckedChange = { replaceLiteral(session, current.start, current.end, current.toggled()) },
                        modifier = Modifier.requiredSize(52.dp, 32.dp).scale(0.7f).semantics { contentDescription = description },
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberControls(session: EditorSession, literal: TweakableLiteral.Number, onDragging: (Boolean) -> Unit) {
    val latest by rememberUpdatedState(literal)
    val stepPx = with(LocalDensity.current) { SCRUB_STEP.toPx() }
    fun step(by: Int) {
        val l = latest
        replaceLiteral(session, l.start, l.end, l.format(l.value + by * l.step))
    }
    StepButton(CaIcons.minus, stringResource(Res.string.literal_tweak_decrease)) { step(-1) }

    // The scrub handle: drag right to increase, left to decrease, one step per [SCRUB_STEP]. The base value and
    // the range being rewritten are captured at drag start; only a change of step rewrites the text.
    var inBatch by remember { mutableStateOf(false) }
    DisposableEffect(session) { onDispose { if (inBatch) { session.endBatch(); inBatch = false; onDragging(false) } } }
    val scrubDescription = stringResource(Res.string.literal_tweak_scrub)
    Box(
        Modifier
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(Ca.radius.control))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = scrubDescription }
            .pointerInput(session) {
                var base: TweakableLiteral.Number? = null
                var end = 0
                var accumulated = 0f
                var applied = 0
                fun finish() {
                    if (inBatch) { session.endBatch(); inBatch = false }
                    base = null
                    onDragging(false)
                }
                detectHorizontalDragGestures(
                    onDragStart = {
                        base = latest; end = latest.end; accumulated = 0f; applied = 0
                        session.beginBatch(); inBatch = true
                        onDragging(true)
                    },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                ) { change, dx ->
                    change.consume()
                    val b = base ?: return@detectHorizontalDragGestures
                    accumulated += dx
                    val steps = (accumulated / stepPx).toInt()
                    if (steps == applied) return@detectHorizontalDragGestures
                    applied = steps
                    val text = b.format(b.value + steps * b.step)
                    replaceLiteral(session, b.start, end, text)
                    end = b.start + text.length
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            session.doc.substring(literal.start, literal.end.coerceAtMost(session.doc.length)),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }

    StepButton(CaIcons.plus, stringResource(Res.string.literal_tweak_increase)) { step(+1) }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(Ca.radius.control)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/** Rewrite `[start, end)` with [text], leaving the caret at the literal's end so it stays on the literal. */
private fun replaceLiteral(session: EditorSession, start: Int, end: Int, text: String) {
    if (start < 0 || end > session.doc.length || start > end) return
    if (session.doc.substring(start, end) == text) return
    session.replaceRange(start, end, text, TextRange(start + text.length))
}
