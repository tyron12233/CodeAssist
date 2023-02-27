package org.jetbrains.kotlin.com.intellij.util.indexing.contentQueue;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public final class TooLargeContentException extends Exception {
  private final VirtualFile myFile;

  public TooLargeContentException(@NotNull VirtualFile file) {
    myFile = file;
  }

  @NotNull
  public VirtualFile getFile() {
    return myFile;
  }
}