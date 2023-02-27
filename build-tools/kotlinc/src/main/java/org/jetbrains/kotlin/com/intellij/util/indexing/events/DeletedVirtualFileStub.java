package org.jetbrains.kotlin.com.intellij.util.indexing.events;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile;

public final class DeletedVirtualFileStub extends LightVirtualFile implements VirtualFileWithId {
  private final int myFileId;

  public DeletedVirtualFileStub(@NotNull VirtualFileWithId original) {
    setOriginalFile((VirtualFile)original);
    myFileId = original.getId();
  }

  public DeletedVirtualFileStub(int id) {
    myFileId = id;
  }

  @Nullable
  @Override
  public VirtualFile getOriginalFile() {
    return super.getOriginalFile();
  }

  public boolean isOriginalValid() {
    VirtualFile originalFile = getOriginalFile();
    return originalFile != null && originalFile.isValid();
  }

  @Override
  public int getId() {
    return myFileId;
  }

  @Override
  public boolean isValid() {
    return false;
  }

  @Override
  public boolean equals(Object o) {
      if (this == o) {
          return true;
      }
      if (o == null || getClass() != o.getClass()) {
          return false;
      }
    DeletedVirtualFileStub stub = (DeletedVirtualFileStub)o;
    return myFileId == stub.myFileId;
  }

  @Override
  public int hashCode() {
    return myFileId;
  }

  @Override
  public String toString() {
    VirtualFile originalFile = getOriginalFile();
    String fileText = originalFile == null
                      ? ("deleted in previous session (file id = " + myFileId + ")")
                      : originalFile.toString();
    return "invalidated file :" + fileText;
  }
}