package dev.ide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Pins [content] to left-to-right layout whatever the system locale is.
 *
 * Most of the IDE mirrors correctly under an RTL locale, but the views that show machine output do not:
 * source code, build logs, logcat and compiler diagnostics are Latin-script and column-aligned (gutter,
 * timestamp, level, tag, `file:line`, indentation), so mirroring reverses the column order, pushes each
 * line's indent away from the reading edge and moves its trailing punctuation. Those views opt out with
 * this wrapper; the rest of the app keeps its RTL layout.
 */
@Composable
fun LtrContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

/**
 * The quarter turn that takes a disclosure caret from collapsed to expanded, in degrees.
 *
 * The caret is drawn pointing along the reading direction and auto-mirrors, so under RTL it starts out
 * pointing left; turning it clockwise from there would leave it pointing up. Reading the layout
 * direction keeps the open state pointing down either way, and keeps the transition a turn rather than a
 * swap between two glyphs.
 */
@Composable
fun expandedCaretTurn(): Float = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -90f else 90f
