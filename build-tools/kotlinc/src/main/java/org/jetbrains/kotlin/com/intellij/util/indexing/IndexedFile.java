package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolder;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public interface IndexedFile extends UserDataHolder {
  @NonNull
  FileType getFileType();

  @NonNull
  VirtualFile getFile();

  @NonNull String getFileName();

  Project getProject();
}