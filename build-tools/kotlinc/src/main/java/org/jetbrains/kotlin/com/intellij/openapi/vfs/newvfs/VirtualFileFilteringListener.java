package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.*;

public class VirtualFileFilteringListener implements VirtualFileListener {
  private final VirtualFileListener myDelegate;
  @NonNull
  private final VirtualFileSystem myFileSystem;

  public VirtualFileFilteringListener(@NonNull VirtualFileListener delegate, @NonNull VirtualFileSystem fileSystem) {
    myDelegate = delegate;
    myFileSystem = fileSystem;
  }

  private boolean isFromMySystem(@NonNull VirtualFileEvent event) {
    return event.getFile().getFileSystem() == myFileSystem;
  }

  @Override
  public void beforeContentsChange(@NonNull VirtualFileEvent event) {
    if (isFromMySystem(event)) {
      myDelegate.beforeContentsChange(event);
    }
  }

  @Override
  public void beforeFileDeletion(@NonNull VirtualFileEvent event) {
    if (isFromMySystem(event)) {
      myDelegate.beforeFileDeletion(event);
    }
  }

  @Override
  public void beforeFileMovement(@NonNull VirtualFileMoveEvent event) {
    if (isFromMySystem(event)) {
      myDelegate.beforeFileMovement(event);
    }
  }

  @Override
  public void beforePropertyChange(@NonNull VirtualFilePropertyEvent event) {
    if (isFromMySystem(event)) {
      myDelegate.beforePropertyChange(event);
    }
  }

  @Override
  public void contentsChanged(@NonNull VirtualFileEvent event) {
    if (isFromMySystem(event)) {
      myDelegate.contentsChanged(event);
    }
  }

  @Override
  public void fileCopied(@NonNull VirtualFileCopyEvent event) {
    if (isFromMySystem(event)) {
      myDelegate.fileCopied(event);
    }
  }

  @Override
  public void fileCreated(@NonNull VirtualFileEvent event) {
    if (isFromMySystem(event)) {
      myDelegate.fileCreated(event);
    }
  }

  @Override
  public void fileDeleted(@NonNull VirtualFileEvent event) {
    if (isFromMySystem(event)) {
      myDelegate.fileDeleted(event);
    }
  }

  @Override
  public void fileMoved(@NonNull VirtualFileMoveEvent event) {
    if (isFromMySystem(event)) {
      myDelegate.fileMoved(event);
    }
  }

  @Override
  public void propertyChanged(@NonNull VirtualFilePropertyEvent event) {
    if (isFromMySystem(event)) {
      myDelegate.propertyChanged(event);
    }
  }
}