package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.CachedSingletonsRegistry;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.function.Function;
import java.util.function.Supplier;

public abstract class ManagingFS implements FileSystemInterface {
  private static final Supplier<ManagingFS> ourInstance = () -> ApplicationManager.getApplication().getService(ManagingFS.class);

  public static ManagingFS getInstance() {
    return ourInstance.get();
  }

  @Nullable
  public abstract AttributeInputStream readAttribute(@NonNull VirtualFile file, @NonNull FileAttribute att);

  @NonNull
  public abstract AttributeOutputStream writeAttribute(@NonNull VirtualFile file, @NonNull FileAttribute att);

  /**
   * @return a number that's incremented every time something changes for the file: name, size, flags, content.
   * This number has persisted between IDE sessions and so it'll always increase.
   * This method invocation means disk access, so it's not terribly cheap.
   * @deprecated to be dropped as there is no real use for it
   */
  //FIXME RC: drop this method from API -- the only use is in test code
  @Deprecated
  public abstract int getModificationCount(@NonNull VirtualFile fileOrDirectory);

  /**
   * @return a number that's incremented every time something changes in the VFS, i.e. file hierarchy, names, flags, attributes, contents.
   * This only counts modifications done in the current IDE session.
   * @see #getStructureModificationCount()
   * @see #getFilesystemModificationCount()
   * @deprecated to be dropped as there is no real use for it 
   */
  //FIXME RC: drop this method from API -- the only use is in test code
  @Deprecated
  public abstract int getModificationCount();

  /**
   * @return a number that's incremented every time something changes in the VFS structure, i.e. file hierarchy or names.
   * This only counts modifications done in the current IDE session.
   * @see #getModificationCount()
   */
  public abstract int getStructureModificationCount();

  /**
   * @return a number that's incremented every time modification count for some file is advanced, @see {@link #getModificationCount(VirtualFile)}.
   * This number has persisted between IDE sessions and so it'll always increase.
   */
  public abstract int getFilesystemModificationCount();

  public abstract long getCreationTimestamp();

  public abstract boolean areChildrenLoaded(@NonNull VirtualFile dir);

  public abstract boolean wereChildrenAccessed(@NonNull VirtualFile dir);

  @Nullable
  public abstract NewVirtualFile findRoot(@NonNull String path, @NonNull NewVirtualFileSystem fs);

  public abstract VirtualFile [] getRoots();

  public abstract VirtualFile [] getRoots(@NonNull NewVirtualFileSystem fs);

  public abstract VirtualFile  [] getLocalRoots();

  @Nullable
  public abstract VirtualFile findFileById(int id);

  @NonNull
  protected abstract <P, R> Function<P, R> accessDiskWithCheckCanceled(Function<? super P, ? extends R> function);
}