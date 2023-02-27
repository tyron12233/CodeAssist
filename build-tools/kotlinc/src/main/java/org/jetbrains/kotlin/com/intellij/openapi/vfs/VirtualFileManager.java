package org.jetbrains.kotlin.com.intellij.openapi.vfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.CachedSingletonsRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.BulkFileListener;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtil;
import org.jetbrains.kotlin.com.intellij.util.io.URLUtil;
import org.jetbrains.kotlin.com.intellij.util.messages.Topic;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Manages virtual file systems.
 *
 * @see VirtualFileSystem
 */
public abstract class VirtualFileManager implements ModificationTracker {
//  @Topic.AppLevel
  public static final Topic<BulkFileListener> VFS_CHANGES = new Topic<>(BulkFileListener.class, Topic.BroadcastDirection.TO_DIRECT_CHILDREN, true);

  public static final @NonNull ModificationTracker VFS_STRUCTURE_MODIFICATIONS = () -> getInstance().getStructureModificationCount();

  private static final Supplier<VirtualFileManager> ourInstance =
          () -> ApplicationManager.getApplication().getService(VirtualFileManager.class);

  /**
   * Gets the instance of {@code VirtualFileManager}.
   *
   * @return {@code VirtualFileManager}
   */
  public static @NonNull VirtualFileManager getInstance() {
    return ourInstance.get();
  }

  /**
   * Gets VirtualFileSystem with the specified protocol.
   *
   * @param protocol String representing the protocol
   * @return {@link VirtualFileSystem}
   * @see VirtualFileSystem#getProtocol
   */
//  @Contract("null -> null")
  public abstract VirtualFileSystem getFileSystem(@Nullable String protocol);

  /**
   * <p>Refreshes the cached file systems information from the physical file systems synchronously.<p/>
   *
   * <p><strong>Note</strong>: this method should be only called within a write-action
   * (see {@linkplain com.intellij.openapi.application.Application#runWriteAction})</p>
   *
   * @return refresh session ID.
   */
  public abstract long syncRefresh();

  /**
   * Refreshes the cached file systems information from the physical file systems asynchronously.
   * Launches specified action when refresh is finished.
   *
   * @return refresh session ID.
   */
  public abstract long asyncRefresh(@Nullable Runnable postAction);

  public abstract void refreshWithoutFileWatcher(boolean asynchronous);

  /**
   * Searches for a file specified by the given {@link VirtualFile#getUrl() URL}.
   *
   * @param url the URL to find file by
   * @return <code>{@link VirtualFile}</code> if the file was found, {@code null} otherwise
   * @see VirtualFile#getUrl
   * @see VirtualFileSystem#findFileByPath
   * @see #refreshAndFindFileByUrl
   */
  public @Nullable VirtualFile findFileByUrl(@NonNull String url) {
    return null;
  }

  /**
   * Looks for a related {@link VirtualFile} for a given {@link Path}
   *
   * @return <code>{@link VirtualFile}</code> if the file was found, {@code null} otherwise
   * @see VirtualFile#getUrl
   * @see VirtualFileSystem#findFileByPath
   * @see #refreshAndFindFileByUrl
   */
  public @Nullable VirtualFile findFileByNioPath(@NonNull Path path) {
    return null;
  }

  /**
   * <p>Refreshes only the part of the file system needed for searching the file by the given URL and finds file
   * by the given URL.</p>
   *
   * <p>This method is useful when the file was created externally and you need to find <code>{@link VirtualFile}</code>
   * corresponding to it.</p>
   *
   * <p>If this method is invoked not from Swing event dispatch thread, then it must not happen inside a read action.</p>
   *
   * @param url the URL
   * @return <code>{@link VirtualFile}</code> if the file was found, {@code null} otherwise
   * @see VirtualFileSystem#findFileByPath
   * @see VirtualFileSystem#refreshAndFindFileByPath
   */
  public @Nullable VirtualFile refreshAndFindFileByUrl(@NonNull String url) {
    return null;
  }

  /**
   * <p>Refreshes only the part of the file system needed for searching the file by the given URL and finds file
   * by the given URL.</p>
   *
   * <p>This method is useful when the file was created externally and you need to find <code>{@link VirtualFile}</code>
   * corresponding to it.</p>
   *
   * <p>If this method is invoked not from Swing event dispatch thread, then it must not happen inside a read action.</p>
   *
   * @return <code>{@link VirtualFile}</code> if the file was found, {@code null} otherwise
   * @see VirtualFileSystem#findFileByPath
   * @see VirtualFileSystem#refreshAndFindFileByPath
   **/
  public @Nullable VirtualFile refreshAndFindFileByNioPath(@NonNull Path path) {
    return null;
  }

  /**
   * @deprecated Prefer {@link #addVirtualFileListener(VirtualFileListener, Disposable)} or other VFS listeners.
   */
  @Deprecated
  public abstract void addVirtualFileListener(@NonNull VirtualFileListener listener);

  /**
   * @deprecated When possible, migrate to {@link AsyncFileListener} to process events on a pooled thread.
   * Otherwise, consider using {@link #VFS_CHANGES} message bus topic to avoid early initialization of {@link VirtualFileManager}.
   */
  @Deprecated
  public abstract void addVirtualFileListener(@NonNull VirtualFileListener listener, @NonNull Disposable parentDisposable);

  /**
   * @deprecated Prefer {@link #addVirtualFileListener(VirtualFileListener, Disposable)} or other VFS listeners.
   */
  @Deprecated
  public abstract void removeVirtualFileListener(@NonNull VirtualFileListener listener);

  /**
   * Consider using extension point {@code vfs.asyncListener}.
   */
  public abstract void addAsyncFileListener(@NonNull AsyncFileListener listener, @NonNull Disposable parentDisposable);

  /**
   * Constructs a {@link VirtualFile#getUrl() URL} by specified protocol and path.
   *
   * @param protocol the protocol
   * @param path     the path
   * @return URL
   * @see VirtualFile#getUrl
   */
  public static @NonNull String constructUrl(@NonNull String protocol, @NonNull String path) {
    return protocol + "://" + path;
  }

  /**
   * Extracts protocol from the given URL. Protocol is a substring from the beginning of the URL till "://".
   *
   * @param url the URL
   * @return protocol or {@code null} if there is no "://" in the URL
   * @see VirtualFileSystem#getProtocol
   */
  public static @Nullable String extractProtocol(@NonNull String url) {
    int index = url.indexOf("://");
      if (index < 0) {
          return null;
      }
    return url.substring(0, index);
  }

  /**
   * @see URLUtil#extractPath(String)
   */
  public static @NonNull String extractPath(@NonNull String url) {
    return URLUtil.extractPath(url);
  }

  /**
   * @deprecated Use {@link #addVirtualFileManagerListener(VirtualFileManagerListener, Disposable)}
   */
  @Deprecated
  public abstract void addVirtualFileManagerListener(@NonNull VirtualFileManagerListener listener);

  public abstract void addVirtualFileManagerListener(@NonNull VirtualFileManagerListener listener, @NonNull Disposable parentDisposable);

  /**
   * @deprecated Use {@link #addVirtualFileManagerListener(VirtualFileManagerListener, Disposable)}
   */
  @Deprecated
  public abstract void removeVirtualFileManagerListener(@NonNull VirtualFileManagerListener listener);

  public abstract void notifyPropertyChanged(@NonNull VirtualFile virtualFile,
                                             @NonNull String property,
                                             Object oldValue,
                                             Object newValue);

  /**
   * @return a number that's incremented every time something changes in the VFS, i.e. file hierarchy, names, flags, attributes, contents.
   * This only counts modifications done in current IDE session.
   * @see #getStructureModificationCount()
   */
  @Override
  public abstract long getModificationCount();

  /**
   * @return a number that's incremented every time something changes in the VFS structure, i.e. file hierarchy or names.
   * This only counts modifications done in current IDE session.
   * @see #getModificationCount()
   */
  public abstract long getStructureModificationCount();

//  @ApiStatus.Internal
  public VirtualFile findFileById(int id) {
    return null;
  }

//  @ApiStatus.Internal
  public int[] listAllChildIds(int id) {
    return ArrayUtil.EMPTY_INT_ARRAY;
  }

//  @ApiStatus.Internal
  public abstract int storeName(@NonNull String name);

//  @ApiStatus.Internal
  public abstract @NonNull CharSequence getVFileName(int nameId);
}