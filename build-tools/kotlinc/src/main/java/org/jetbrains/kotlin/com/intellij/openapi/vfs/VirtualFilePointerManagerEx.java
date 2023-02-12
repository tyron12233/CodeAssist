package org.jetbrains.kotlin.com.intellij.openapi.vfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.SimpleModificationTracker;

public abstract class VirtualFilePointerManagerEx extends SimpleModificationTracker {
  public static VirtualFilePointerManagerEx getInstance() {
    return ApplicationManager.getApplication().getService(VirtualFilePointerManagerEx.class);
  }

  @NonNull
  public abstract VirtualFilePointer create(@NonNull String url, @NonNull Disposable parent, @Nullable VirtualFilePointerListener listener);

  @NonNull
  public abstract VirtualFilePointer create(@NonNull VirtualFile file, @NonNull Disposable parent, @Nullable VirtualFilePointerListener listener);

  @NonNull
  public abstract VirtualFilePointer duplicate(@NonNull VirtualFilePointer pointer, @NonNull Disposable parent,
                                               @Nullable VirtualFilePointerListener listener);

  @NonNull
  public abstract VirtualFilePointerContainer createContainer(@NonNull Disposable parent);

  @NonNull
  public abstract VirtualFilePointerContainer createContainer(@NonNull Disposable parent, @Nullable VirtualFilePointerListener listener);

  @NonNull
  public abstract VirtualFilePointer createDirectoryPointer(@NonNull String url,
                                                            boolean recursively,
                                                            @NonNull Disposable parent,
                                                            @NonNull VirtualFilePointerListener listener);
}