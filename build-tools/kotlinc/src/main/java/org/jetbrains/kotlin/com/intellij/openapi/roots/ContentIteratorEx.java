package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.containers.TreeNodeProcessingResult;

/**
 * Interface which can be used to receive the contents of a project.
 *
 * @see FileIndex#iterateContent(ContentIterator)
 */
@FunctionalInterface
public interface ContentIteratorEx extends ContentIterator {
  /**
   * Processes the specified file or directory.
   */
  @NonNull
  TreeNodeProcessingResult processFileEx(@NonNull VirtualFile fileOrDir);

  @Override
  default boolean processFile(@NonNull VirtualFile fileOrDir) {
    throw new IllegalStateException("Call com.intellij.openapi.roots.ContentIteratorEx#processFileEx instead");
  }
}