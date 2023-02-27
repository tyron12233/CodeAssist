package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * A special exception which tells that storage accessed after it has been closed.
 * In this case no need to mark it as corrupted,
 * just propagate this exception or catch it somewhere and rethrow PCE.
 */
public final class ClosedStorageException extends IOException {
  public ClosedStorageException(@NonNull String message) {
    super(message);
  }
}