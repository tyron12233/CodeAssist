package dev.ide.ui.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState

/**
 * Scroll the item at [index] into view with the shortest move that gets it there: an item clipped by one of
 * the viewport edges slides just past that edge, and an item that is already fully on screen does not move
 * the list at all, so revealing what the user is already looking at is free. An item outside the measured
 * window is jumped to instead, which also covers the item that was added in the very frame this runs (the
 * layout it would be measured in has not happened yet).
 */
internal suspend fun LazyListState.revealItem(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val delta = when {
        item.offset < info.viewportStartOffset -> item.offset - info.viewportStartOffset
        item.offset + item.size > info.viewportEndOffset -> item.offset + item.size - info.viewportEndOffset
        else -> 0
    }
    if (delta != 0) animateScrollBy(delta.toFloat())
}
