package dev.ide.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.gesture_nothing_to_redo
import dev.ide.ui.generated.resources.gesture_nothing_to_undo
import dev.ide.ui.generated.resources.redo
import dev.ide.ui.generated.resources.undo
import dev.ide.ui.icons.CaIcons
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** What the last undo gesture did, for the confirmation label; [serial] re-shows it for a repeat of the same step. */
internal class UndoGestureFeedback {
    enum class Kind { UNDO, REDO, NOTHING_TO_UNDO, NOTHING_TO_REDO }

    var kind by mutableStateOf<Kind?>(null)
        private set
    var serial by mutableIntStateOf(0)
        private set

    fun show(kind: Kind) {
        this.kind = kind
        serial++
    }

    fun hide() {
        kind = null
    }
}

/**
 * Three-finger swipe: left undoes, right redoes, as on iOS. Every [UndoSwipeStep] of travel takes one more step,
 * so a long swipe undoes several edits. Watched on the Initial pass, before the editor's scrolling, pinch-zoom and
 * taps see the events, and claimed (consumed) only once three fingers are down and moving sideways, so a two-finger
 * pinch or a one-finger scroll is untouched. Touch only: a trackpad's three-finger swipe belongs to the OS.
 */
internal fun Modifier.undoSwipe(session: EditorSession, feedback: UndoGestureFeedback): Modifier =
    pointerInput(session) {
        awaitEachGesture {
            var claimed = false
            var travel = 0f
            val step = UndoSwipeStep.toPx()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val touching = event.changes.filter { it.pressed && it.type == PointerType.Touch }
                if (touching.isEmpty()) break
                if (touching.size < 3) {
                    if (claimed) event.changes.forEach { it.consume() } // a finger lifted early: still ours
                    continue
                }
                // The fingers' average movement this event.
                val dx = touching.sumOf { (it.position.x - it.previousPosition.x).toDouble() }.toFloat() / touching.size
                val dy = touching.sumOf { (it.position.y - it.previousPosition.y).toDouble() }.toFloat() / touching.size
                if (!claimed && abs(dx) > abs(dy) && abs(travel + dx) > viewConfiguration.touchSlop) claimed = true
                travel += dx
                if (claimed) {
                    event.changes.forEach { it.consume() }
                    while (travel <= -step) {
                        travel += step
                        feedback.show(if (session.undo()) UndoGestureFeedback.Kind.UNDO else UndoGestureFeedback.Kind.NOTHING_TO_UNDO)
                    }
                    while (travel >= step) {
                        travel -= step
                        feedback.show(if (session.redo()) UndoGestureFeedback.Kind.REDO else UndoGestureFeedback.Kind.NOTHING_TO_REDO)
                    }
                }
            }
        }
    }

/** The label an undo gesture raises at the top of the editor, gone ~0.8s after the last step. */
@Composable
internal fun BoxScope.UndoGestureLabel(feedback: UndoGestureFeedback) {
    var shown by androidx.compose.runtime.remember { mutableStateOf<UndoGestureFeedback.Kind?>(null) }
    LaunchedEffect(feedback.serial) {
        val k = feedback.kind ?: return@LaunchedEffect
        shown = k
        delay(800)
        shown = null
        feedback.hide()
    }
    AnimatedVisibility(
        visible = shown != null,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
    ) {
        val kind = shown ?: feedback.kind ?: UndoGestureFeedback.Kind.UNDO
        val (icon, text) = when (kind) {
            UndoGestureFeedback.Kind.UNDO -> CaIcons.undo to stringResource(Res.string.undo)
            UndoGestureFeedback.Kind.REDO -> CaIcons.redo to stringResource(Res.string.redo)
            UndoGestureFeedback.Kind.NOTHING_TO_UNDO -> CaIcons.undo to stringResource(Res.string.gesture_nothing_to_undo)
            UndoGestureFeedback.Kind.NOTHING_TO_REDO -> CaIcons.redo to stringResource(Res.string.gesture_nothing_to_redo)
        }
        val dim = kind == UndoGestureFeedback.Kind.NOTHING_TO_UNDO || kind == UndoGestureFeedback.Kind.NOTHING_TO_REDO
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = if (dim) 0.7f else 1f)
            Icon(icon, null, Modifier.size(16.dp), tint = ink)
            Text(text, color = ink, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Sideways travel of three fingers per undo or redo step. */
private val UndoSwipeStep = 72.dp
