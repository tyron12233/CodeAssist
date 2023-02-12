package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.EventListener;

/**
 *  Root provider for order entry
 */
public interface RootProvider {
  String [] getUrls(@NonNull OrderRootType rootType);
  VirtualFile[] getFiles(@NonNull OrderRootType rootType);

  @FunctionalInterface
  interface RootSetChangedListener extends EventListener {
    void rootSetChanged(@NonNull RootProvider wrapper);
  }

  void addRootSetChangedListener(@NonNull RootSetChangedListener listener);
  void addRootSetChangedListener(@NonNull RootSetChangedListener listener, @NonNull Disposable parentDisposable);
  void removeRootSetChangedListener(@NonNull RootSetChangedListener listener);
}