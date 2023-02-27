package org.jetbrains.kotlin.com.intellij.util.indexing;

/**
 * An exception intended to report that {@link InvertedIndex} storage looks like corrupted and should be rebuilt.
 */
public final class StorageException extends Exception {
  public StorageException(final String message) {
    super(message);
  }

  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }

  public StorageException(Throwable cause) {
    super(cause);
  }
}