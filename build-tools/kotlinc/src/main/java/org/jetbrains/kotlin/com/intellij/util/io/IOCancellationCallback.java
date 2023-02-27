package org.jetbrains.kotlin.com.intellij.util.io;

import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;

/**
 * Check whether the current process should be terminated to avoid long IO-operation
 * and throws cancellation exception if it does.
 */
public interface IOCancellationCallback {
  void checkCancelled() throws ProcessCanceledException;

  void interactWithUI();
}