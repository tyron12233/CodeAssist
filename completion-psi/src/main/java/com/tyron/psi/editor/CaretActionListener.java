package com.tyron.psi.editor;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;

import java.util.EventListener;

/**
 * A listener which will be notified when {@link CaretModel#runForEachCaret(CaretAction)} or
 * {@link CaretModel#runForEachCaret(CaretAction, boolean)} is invoked in EDT (those methods can also be invoked from other threads,
 * but no changes to caret state can be performed from there).
 *
 * @see CaretModel#addCaretActionListener(CaretActionListener, Disposable)
 */
public interface CaretActionListener extends EventListener {
    default void beforeAllCaretsAction() {}

    default void afterAllCaretsAction() {}
}