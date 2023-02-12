package org.jetbrains.kotlin.com.intellij.openapi.vfs.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerContainer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerManagerEx;

public class CoreVirtualFilePointerManagerEx extends VirtualFilePointerManagerEx implements Disposable {
  @NonNull
  @Override
  public VirtualFilePointer create(@NonNull String url, @NonNull Disposable parent, @Nullable VirtualFilePointerListener listener) {
    return new LightFilePointer(url);
  }

  @NonNull
  @Override
  public VirtualFilePointer create(@NonNull VirtualFile file, @NonNull Disposable parent, @Nullable VirtualFilePointerListener listener) {
    return new LightFilePointer(file);
  }

  @NonNull
  @Override
  public VirtualFilePointer duplicate(@NonNull VirtualFilePointer pointer,
                                      @NonNull Disposable parent,
                                      @Nullable VirtualFilePointerListener listener) {
    return new LightFilePointer(pointer.getUrl());
  }

  @NonNull
  @Override
  public VirtualFilePointerContainer createContainer(@NonNull Disposable parent) {
    return createContainer(parent, null);
  }

  @NonNull
  @Override
  public VirtualFilePointerContainer createContainer(@NonNull Disposable parent, @Nullable VirtualFilePointerListener listener) {
    return new VirtualFilePointerContainerImpl(this, parent, listener);
  }

  @NonNull
  @Override
  public VirtualFilePointer createDirectoryPointer(@NonNull String url,
                                                   boolean recursively,
                                                   @NonNull Disposable parent, @NonNull VirtualFilePointerListener listener) {
    return create(url, parent, listener);
  }

  @Override
  public void dispose() {

  }
}