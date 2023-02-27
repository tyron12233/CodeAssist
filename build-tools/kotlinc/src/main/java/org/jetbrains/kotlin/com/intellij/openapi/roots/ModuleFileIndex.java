package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

import java.util.List;

/**
 * Provides information about files contained in a module. Should be used from a read action.
 *
 * @see ModuleRootManager#getFileIndex()
 */
public interface ModuleFileIndex extends FileIndex {
  /**
   * Returns the order entry to which the specified file or directory
   * belongs.
   *
   * @param fileOrDir the file or directory to check.
   * @return the order entry to which the file or directory belongs, or null if
   * it does not belong to any order entry.
   */
  @Nullable
  OrderEntry getOrderEntryForFile(@NonNull VirtualFile fileOrDir);

  /**
   * Returns the list of all order entries to which the specified file or directory
   * belongs.
   *
   * @param fileOrDir the file or directory to check.
   * @return the list of order entries to which the file or directory belongs.
   */
  @NonNull List<OrderEntry> getOrderEntriesForFile(@NonNull VirtualFile fileOrDir);
}