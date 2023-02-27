package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;

public interface FileSystemInterface {
  // default values for missing files (same as in corresponding java.io.File methods)
  long DEFAULT_LENGTH = 0;
  long DEFAULT_TIMESTAMP = 0;

  boolean exists(@NonNull VirtualFile file);

  String[] list(@NonNull VirtualFile file);

  boolean isDirectory(@NonNull VirtualFile file);

  long getTimeStamp(@NonNull VirtualFile file);
  void setTimeStamp(@NonNull VirtualFile file, long timeStamp) throws IOException;

  boolean isWritable(@NonNull VirtualFile file);
  void setWritable(@NonNull VirtualFile file, boolean writableFlag) throws IOException;

  boolean isSymLink(@NonNull VirtualFile file);
  @Nullable String resolveSymLink(@NonNull VirtualFile file);

  /**
   * Returns all virtual files under which the given path is known in the VFS, starting with virtual file for the passed path.
   * Please note, that it is guaranteed to find all aliases only if path is canonical.
   */
  default @NonNull Iterable<VirtualFile> findCachedFilesForPath(@NonNull String path) {
    return Collections.emptyList();
  }

  @NonNull VirtualFile createChildDirectory(@Nullable Object requestor, @NonNull VirtualFile parent, @NonNull String dir) throws IOException;
  @NonNull VirtualFile createChildFile(@Nullable Object requestor, @NonNull VirtualFile parent, @NonNull String file) throws IOException;

  void deleteFile(Object requestor, @NonNull VirtualFile file) throws IOException;
  void moveFile(Object requestor, @NonNull VirtualFile file, @NonNull VirtualFile newParent) throws IOException;
  void renameFile(Object requestor, @NonNull VirtualFile file, @NonNull String newName) throws IOException;

  @NonNull VirtualFile copyFile(Object requestor, @NonNull VirtualFile file, @NonNull VirtualFile newParent, @NonNull String copyName) throws IOException;

  byte[] contentsToByteArray(@NonNull VirtualFile file) throws IOException;

  /** Does NOT strip the BOM from the beginning of the stream, unlike the {@link VirtualFile#getInputStream()} */
  @NonNull InputStream getInputStream(@NonNull VirtualFile file) throws IOException;

  /** Does NOT add the BOM to the beginning of the stream, unlike the {@link VirtualFile#getOutputStream(Object)} */
  @NonNull OutputStream getOutputStream(@NonNull VirtualFile file, Object requestor, long modStamp, long timeStamp) throws IOException;

  long getLength(@NonNull VirtualFile file);
}
