package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;

/**
 * Interface which can be used to receive the contents of a project.
 *
 * @see FileIndex#iterateContent(ContentIterator)
 */
@FunctionalInterface
public interface ContentIterator {
  /**
   * Processes the specified file or directory.
   *
   * @param fileOrDir the file or directory to process.
   * @return false if files processing should be stopped, true if it should be continued.
   */
  boolean processFile(@NonNull VirtualFile fileOrDir);


}