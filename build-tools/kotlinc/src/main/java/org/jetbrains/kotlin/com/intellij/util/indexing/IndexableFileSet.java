package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

@ApiStatus.Internal
public interface IndexableFileSet {
  boolean isInSet(@NotNull VirtualFile file);
}