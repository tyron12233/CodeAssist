package dev.ide.swing.event

import dev.ide.awt.AWTEvent

/** `javax.swing.event.ListSelectionEvent`: the selected rows of a list changed. */
class ListSelectionEvent(
    source: Any?,
    private val firstIndex: Int,
    private val lastIndex: Int,
    private val isAdjusting: Boolean,
) : AWTEvent(source, 0) {
    fun getFirstIndex(): Int = firstIndex
    fun getLastIndex(): Int = lastIndex

    /** True while the selection is still being dragged out. Always false here: this toolkit has no drag
     *  selection, so every change it reports is a final one. */
    fun getValueIsAdjusting(): Boolean = isAdjusting
}

/** `javax.swing.event.ListSelectionListener`. */
fun interface ListSelectionListener {
    fun valueChanged(e: ListSelectionEvent)
}

/** `javax.swing.event.ChangeEvent`: something's value moved, with no detail beyond the source. */
class ChangeEvent(source: Any?) : AWTEvent(source, 0)

/** `javax.swing.event.ChangeListener`. */
fun interface ChangeListener {
    fun stateChanged(e: ChangeEvent)
}
