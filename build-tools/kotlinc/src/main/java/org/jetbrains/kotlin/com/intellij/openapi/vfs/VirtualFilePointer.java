package org.jetbrains.kotlin.com.intellij.openapi.vfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A pointer to a {@link VirtualFile}.
 *
 * @see VirtualFilePointerManagerEx#create
 * @see VirtualFilePointerContainer
 */
public interface VirtualFilePointer {
  VirtualFilePointer[] EMPTY_ARRAY = new VirtualFilePointer[0];

  @NonNull
  String getFileName();

  @Nullable
  VirtualFile getFile();

  @NonNull
  String getUrl();

  @NonNull String getPresentableUrl();

  /**
   * @return true if the file exists
   */
  boolean isValid();
}