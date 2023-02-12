package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

/**
 * Represents an entry in the classpath of a module (as shown in the "Order/Export" page
 * of the module configuration dialog).
 */
public interface OrderEntry extends Synthetic, Comparable<OrderEntry> {
  /**
   * The empty array of order entries which can be reused to avoid unnecessary allocations.
   */
  OrderEntry[] EMPTY_ARRAY = new OrderEntry[0];

  /**
   * Returns the list of root {@link VirtualFile}s of the given type for this entry.
   * @deprecated the meaning of this method is unclear. 
   * If this instance represents dependency on a library or an SDK, use {@link LibraryOrSdkOrderEntry#getRootFiles(OrderRootType)} instead.
   * In other cases, use {@link OrderEnumerator} and specify what files from dependencies of a module you want to get.
   */
  @Deprecated
  VirtualFile[] getFiles(@NonNull OrderRootType type);

  /**
   * Returns the list of roots of the given type for this entry.
   *
   * @deprecated the meaning of this method is unclear.
   * If this instance represents dependency on a library or an SDK, use {@link LibraryOrSdkOrderEntry#getRootUrls(OrderRootType)} instead.
   * In other cases, use {@link OrderEnumerator} and specify what files from dependencies of a module you want to get.
   */
  @Deprecated
  String[] getUrls(@NonNull OrderRootType rootType);

  /**
   * Returns the user-visible name of this OrderEntry.
   *
   * @return name of this OrderEntry to be shown to user.
   */
  @NonNull
  String getPresentableName();

  /**
   * Checks whether this order entry is invalid for some reason. Note that entry being valid
   * does not necessarily mean that all its roots are valid.
   *
   * @return true if entry is valid, false otherwise.
   */
  boolean isValid();

  /**
   * Returns the module to which the entry belongs.
   *
   * @return the module instance.
   */
  @NonNull
  Module getOwnerModule();

  /**
   * Accepts the specified order entries visitor.
   *
   * @param policy       the visitor to accept.
   * @param initialValue the default value to be returned by the visit process.
   * @return the value returned by the visitor.
   */
  <R> R accept(@NonNull RootPolicy<R> policy, @Nullable R initialValue);
}