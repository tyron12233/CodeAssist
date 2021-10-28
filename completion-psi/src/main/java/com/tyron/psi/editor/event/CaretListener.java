package com.tyron.psi.editor.event;

import com.tyron.psi.editor.Caret;
import com.tyron.psi.editor.CaretModel;

import org.jetbrains.annotations.NotNull;

import java.util.EventListener;

/**
 * Allows to receive notifications about caret movement, and caret additions/removal
 *
 * @see CaretModel#addCaretListener(CaretListener)
 * @see EditorEventMulticaster#addCaretListener(CaretListener, Disposable)
 */
public interface CaretListener extends EventListener {
    /**
     * Called when the caret position has changed.<p>
     * Only explicit caret movements (caused by 'move' methods in {@link Caret} and {@link CaretModel}) are reported, 'induced' changes of
     * caret offset due to document modifications are not reported.
     *
     * @param event the event containing information about the caret movement.
     */
    default void caretPositionChanged(@NotNull CaretEvent event) {
    }

    /**
     * Called when a new caret was added to the document.
     */
    default void caretAdded(@NotNull CaretEvent event) {
    }

    /**
     * Called when a caret was removed from the document.
     */
    default void caretRemoved(@NotNull CaretEvent event) {
    }
}