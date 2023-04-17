package com.tyron.editor.event;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentListener;

/**
 * Allows to attach listeners which receive notifications about changes in any currently open
 * editor.
 *
 * @see com.tyron.editor.EditorFactory#getEventMulticaster()
 */
public interface EditorEventMulticaster {
  /**
   * @deprecated Use {@link #addDocumentListener(DocumentListener, Disposable)} instead to avoid
   * leaking listeners
   */
  @Deprecated
  void addDocumentListener(@NotNull DocumentListener listener);

  void addDocumentListener(@NotNull DocumentListener listener, @NotNull Disposable parentDisposable);

  void removeDocumentListener(@NotNull DocumentListener listener);
}