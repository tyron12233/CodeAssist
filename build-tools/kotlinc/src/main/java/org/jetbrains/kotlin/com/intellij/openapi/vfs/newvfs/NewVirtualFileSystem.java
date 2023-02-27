package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileAttributes;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.ex.VirtualFileManagerEx;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class NewVirtualFileSystem extends VirtualFileSystem implements FileSystemInterface, CachingVirtualFileSystem {
  private final Map<VirtualFileListener, VirtualFileListener> myListenerWrappers = new ConcurrentHashMap<>();

  /**
   * <p>Implementations <b>should</b> convert separator chars to forward slashes and remove duplicates ones,
   * and convert paths to "absolute" form (so that they start from a root that is valid for this FS and
   * could be later extracted with {@link #extractRootPath}).</p>
   *
   * <p>Implementations <b>should not</b> normalize paths by eliminating directory traversals or other indirections.</p>
   *
   * @return a normalized path, or {@code null} when a path is invalid for this FS.
   */
  protected @Nullable String normalize(@NonNull String path) {
    return path;
  }

  /**
   * IntelliJ platform calls this method with non-null value returned by {@link #normalize}, but if something went wrong
   * and an implementation can't extract a valid root path nevertheless, it should return an empty string.
   */
  protected abstract @NonNull String extractRootPath(@NonNull String normalizedPath);

  public abstract @Nullable VirtualFile findFileByPathIfCached(@NonNull String path);

  @Override
  public void refreshWithoutFileWatcher(boolean asynchronous) {
    refresh(asynchronous);
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public boolean isSymLink(@NonNull VirtualFile file) {
    return false;
  }

  @Override
  public String resolveSymLink(@NonNull VirtualFile file) {
    return null;
  }

  @Override
  public void addVirtualFileListener(@NonNull VirtualFileListener listener) {
    VirtualFileListener wrapper = new VirtualFileFilteringListener(listener, this);
    //noinspection deprecation
    VirtualFileManager.getInstance().addVirtualFileListener(wrapper);
    myListenerWrappers.put(listener, wrapper);
  }

  @Override
  public void removeVirtualFileListener(@NonNull VirtualFileListener listener) {
    VirtualFileListener wrapper = myListenerWrappers.remove(listener);
    if (wrapper != null) {
      //noinspection deprecation
      VirtualFileManagerEx.getInstance().removeVirtualFileListener(wrapper);
    }
  }

  public abstract int getRank();

  @Override
  public abstract @NonNull VirtualFile copyFile(Object requestor, @NonNull VirtualFile file, @NonNull VirtualFile newParent, @NonNull String copyName) throws IOException;

  @Override
  public abstract @NonNull VirtualFile createChildDirectory(Object requestor, @NonNull VirtualFile parent, @NonNull String dir) throws IOException;

  @Override
  public abstract @NonNull VirtualFile createChildFile(Object requestor, @NonNull VirtualFile parent, @NonNull String file) throws IOException;

  @Override
  public abstract void deleteFile(Object requestor, @NonNull VirtualFile file) throws IOException;

  @Override
  public abstract void moveFile(Object requestor, @NonNull VirtualFile file, @NonNull VirtualFile newParent) throws IOException;

  @Override
  public abstract void renameFile(Object requestor, @NonNull VirtualFile file, @NonNull String newName) throws IOException;

  public boolean markNewFilesAsDirty() {
    return false;
  }

  public @NonNull String getCanonicallyCasedName(@NonNull VirtualFile file) {
    return file.getName();
  }

  /**
   * Reads various file attributes in one shot (to reduce the number of native I/O calls).
   *
   * @param file file to get attributes of.
   * @return attributes of a given file, or {@code null} if the file doesn't exist.
   */
  public abstract @Nullable FileAttributes getAttributes(@NonNull VirtualFile file);

  /**
   * Returns {@code true} if {@code path} represents a directory with at least one child.
   * Override if your file system can answer this question more efficiently (e.g. without enumerating all children).
   */
  public boolean hasChildren(@NonNull VirtualFile file) {
    return list(file).length != 0;
  }
}