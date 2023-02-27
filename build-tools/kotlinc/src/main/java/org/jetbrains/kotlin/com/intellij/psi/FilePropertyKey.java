package org.jetbrains.kotlin.com.intellij.psi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

/**
 * Stores and retrieves values, associated with a {@link  VirtualFile} and surviving IDE restarts.
 * <p>
 * Main use case is to provide a storage for pushed file properties by Indexes, so on IDE restart previous state can be retrieved from
 * hard drive directly, not from pushers.
 *
 * @param <T> type of value to store and retrieve
 */
@ApiStatus.Internal
@ApiStatus.Experimental
public interface FilePropertyKey<T> {
  /**
   * Retrieves persistent value associated with {@code virtualFile}
   *
   * @param virtualFile file to associate value with
   * @return previously stored value, or {@code null}
   */
  @Contract("null -> null")
  T getPersistentValue(@Nullable VirtualFile virtualFile);

  /**
   * Updates persistent value associated with {@code virtualFile}
   *
   * @param virtualFile file to store new value to
   * @param value       new value to store
   * @return {@code true} if value has changed. {@code false} otherwise
   */
  @Contract("null,_ -> false")
  boolean setPersistentValue(@Nullable VirtualFile virtualFile, T value);
}