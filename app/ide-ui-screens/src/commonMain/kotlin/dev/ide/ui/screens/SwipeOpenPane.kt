package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Opens a side pane from a swipe over the content, by the rules the phone's push drawer uses so it never
 * fights the code editor: only the scroll the content left over counts (the editor is at its horizontal
 * start), the stroke must be horizontal overall, and a stroke that scrolled the editor sideways at any point
 * keeps that axis until the finger lifts.
 *
 * The wide layout needs it in zen mode, where the stripes and the top bar that open a tool window are gone: a
 * phone in landscape is wide enough for that layout and has no keyboard shortcut to fall back on.
 *
 * [direction] is +1 when a rightward swipe opens the pane (a left pane under LTR), -1 when a leftward one does.
 */
internal class SwipeOpenConnection(
    private val thresholdPx: Float,
    private val direction: Float,
    private val onOpen: () -> Unit,
) : NestedScrollConnection {
    private var sumX = 0f
    private var sumY = 0f
    private var leak = 0f
    private var childOwnsX = false
    private var fired = false

    private fun reset() { sumX = 0f; sumY = 0f; leak = 0f; childOwnsX = false; fired = false }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        sumX += consumed.x + available.x
        sumY += consumed.y + available.y
        if (consumed.x != 0f) childOwnsX = true
        if (fired || childOwnsX) return Offset.Zero
        leak = (leak + direction * available.x).coerceAtLeast(0f)
        if (leak > thresholdPx && abs(sumX) > abs(sumY)) {
            fired = true
            onOpen()
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        reset()
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        reset()
        return Velocity.Zero
    }
}

/** Calls [onOpen] when a swipe toward the content's end runs past what the content could scroll; see
 *  [SwipeOpenConnection]. A no-op modifier while not [enabled]. */
@Composable
internal fun Modifier.swipeOpensStartPane(enabled: Boolean, onOpen: () -> Unit): Modifier {
    if (!enabled) return this
    val open = rememberUpdatedState(onOpen)
    val thresholdPx = with(LocalDensity.current) { SwipeOpenThreshold.toPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val connection = remember(thresholdPx, direction) { SwipeOpenConnection(thresholdPx, direction) { open.value() } }
    return nestedScroll(connection)
}

/** How far past the content's own scroll a swipe travels before the pane opens: past touch slop, short of a
 *  deliberate drag across the screen. */
private val SwipeOpenThreshold = 40.dp
