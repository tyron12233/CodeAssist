package org.jetbrains.kotlin.com.intellij.openapi.vfs.ex;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;

public abstract class VirtualFileManagerEx extends VirtualFileManager {
  public abstract void fireBeforeRefreshStart(boolean asynchronous);
  public abstract void fireAfterRefreshFinish(boolean asynchronous);
}