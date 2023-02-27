package org.jetbrains.kotlin.com.intellij.util.indexing.contentQueue;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public final class FailedToLoadContentException extends Exception {
  private final VirtualFile myFile;

  public FailedToLoadContentException(@NotNull VirtualFile file, @NotNull Throwable cause) {
    super(cause);
    myFile = file;
  }

  @NotNull
  public VirtualFile getFile() {
    return myFile;
  }
}