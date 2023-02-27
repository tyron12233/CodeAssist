package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

public interface CachingVirtualFileSystem {
  void refreshWithoutFileWatcher(boolean asynchronous);
}