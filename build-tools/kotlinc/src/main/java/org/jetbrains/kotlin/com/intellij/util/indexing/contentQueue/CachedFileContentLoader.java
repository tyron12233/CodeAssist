package org.jetbrains.kotlin.com.intellij.util.indexing.contentQueue;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public interface CachedFileContentLoader {
  @NotNull
  CachedFileContent loadContent(@NotNull VirtualFile file) throws ProcessCanceledException,
                                                                  TooLargeContentException,
                                                                  FailedToLoadContentException;
}