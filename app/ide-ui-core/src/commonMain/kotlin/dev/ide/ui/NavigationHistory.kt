package dev.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** A place the user was: a file and the caret in it. [line] (0-based) is what makes two places the same one. */
data class NavPlace(val path: String, val offset: Int, val line: Int)

/**
 * Navigate Back / Forward (IntelliJ's `IdeDocumentHistory`): where the user was before each jump.
 *
 * A jump (go to declaration, a usage, a search result, a tab switch) [record]s the place it leaves. [back]
 * returns the last such place and keeps the one it leaves for [forward]; a new jump clears the forward list, as a
 * browser does. Two places on the same line of the same file are one, so a jump that goes nowhere (or a tab
 * switch right after a go-to, which both record the same spot) leaves a single entry.
 *
 * Places hold offsets as they were; an edit above one moves its text, not the place, and a stale offset is
 * clamped into the file when it is visited.
 */
class NavigationHistory(private val limit: Int = 100) {
    private val backStack = ArrayDeque<NavPlace>()
    private val forwardStack = ArrayDeque<NavPlace>()

    // Snapshot state, so the Back / Forward buttons enable and disable as the stacks change.
    private var backSize by mutableIntStateOf(0)
    private var forwardSize by mutableIntStateOf(0)

    val canGoBack: Boolean get() = backSize > 0
    val canGoForward: Boolean get() = forwardSize > 0

    /** A jump is leaving [from]: remember it for [back], and drop the forward list. */
    fun record(from: NavPlace?) {
        if (from == null) return
        if (backStack.lastOrNull()?.let { same(it, from) } != true) {
            backStack.addLast(from)
            while (backStack.size > limit) backStack.removeFirst()
        }
        forwardStack.clear()
        sync()
    }

    /** The place to go back to from [current], or null when there is none. [current] is kept for [forward]. */
    fun back(current: NavPlace?): NavPlace? = step(backStack, forwardStack, current)

    /** The place to go forward to from [current], or null when there is none. [current] is kept for [back]. */
    fun forward(current: NavPlace?): NavPlace? = step(forwardStack, backStack, current)

    /** Forget the places whose path [gone] matches (the file was deleted), so Back does not open what is gone. */
    fun forget(gone: (String) -> Boolean) {
        backStack.removeAll { gone(it.path) }
        forwardStack.removeAll { gone(it.path) }
        sync()
    }

    /** Follow a rename or move: each place's path goes through [rebase], which returns null to leave it as is. */
    fun repath(rebase: (String) -> String?) {
        for (stack in listOf(backStack, forwardStack)) {
            for (i in stack.indices) rebase(stack[i].path)?.let { stack[i] = stack[i].copy(path = it) }
        }
    }

    private fun step(from: ArrayDeque<NavPlace>, to: ArrayDeque<NavPlace>, current: NavPlace?): NavPlace? {
        // Skip entries that are where the user already is: going "back" to the same line would look like a no-op.
        while (from.isNotEmpty() && current != null && same(from.last(), current)) from.removeLast()
        val target = from.removeLastOrNull()
        if (target != null && current != null && to.lastOrNull()?.let { same(it, current) } != true) to.addLast(current)
        sync()
        return target
    }

    private fun same(a: NavPlace, b: NavPlace) = a.path == b.path && a.line == b.line

    private fun sync() {
        backSize = backStack.size
        forwardSize = forwardStack.size
    }
}
