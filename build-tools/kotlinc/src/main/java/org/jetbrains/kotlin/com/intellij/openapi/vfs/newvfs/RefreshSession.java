package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.Arrays;
import java.util.Collection;

public abstract class RefreshSession {
  public long getId() {
    return 0;
  }

  public abstract boolean isAsynchronous();

  public abstract void addFile(@NonNull VirtualFile file);

  public abstract void addAllFiles(@NonNull Collection<? extends VirtualFile> files);

  public void addAllFiles(@NonNull VirtualFile ... files) {
    addAllFiles(Arrays.asList(files));
  }

  public abstract void launch();
}