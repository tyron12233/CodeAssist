package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

/**
 * Represents a source or exclude root under the content root of a module.
 *
 * @see ContentEntry#getSourceFolders()
 * @see ContentEntry#getExcludeFolders()
 */
public interface ContentFolder extends Synthetic {
  /**
   * Returns the root file or directory for this root.
   *
   * @return the file or directory, or null if the source path is invalid.
   */
  @Nullable
  VirtualFile getFile();

  /**
   * Returns the content entry to which this root belongs.
   *
   * @return this {@code ContentFolder}s {@link ContentEntry}.
   */
  @NonNull
  ContentEntry getContentEntry();

  /**
   * Returns the URL of the root file or directory for this root.
   *
   * @return the root file or directory URL.
   */
  @NonNull
  String getUrl();
}