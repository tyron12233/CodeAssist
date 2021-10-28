package com.tyron.psi.editor;


import org.jetbrains.annotations.NotNull;

/**
 * Action to be performed on a specific editor caret
 *
 * @see CaretModel#runForEachCaret(CaretAction)
 */
@FunctionalInterface
public interface CaretAction {
    void perform(@NotNull Caret caret);
}