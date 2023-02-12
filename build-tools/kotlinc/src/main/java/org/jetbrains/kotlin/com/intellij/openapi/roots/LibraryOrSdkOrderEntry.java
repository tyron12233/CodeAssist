package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public interface LibraryOrSdkOrderEntry extends OrderEntry {
  /**
   * Returns files configured as roots in the corresponding library or SDK. Note that only existing files are returned; if you need to get
   * paths to non-existing files as well, use {@link #getRootUrls(OrderRootType)} instead.
   */
  VirtualFile[] getRootFiles(@NonNull OrderRootType type);

  /**
   * Returns URLs of roots configured in the corresponding library or SDK.
   */
  String[] getRootUrls(@NonNull OrderRootType type);

  /**
   * @deprecated use {@link #getRootFiles(OrderRootType)} instead; meaning of this method coming from the base {@link OrderEntry} interface 
   * is unclear.
   */
  @Deprecated
  @Override
  default VirtualFile[] getFiles(@NonNull OrderRootType type) {
    return getRootFiles(type);
  }

  /**
   * @deprecated use {@link #getRootUrls(OrderRootType)} instead; meaning of this method coming from the base {@link OrderEntry} interface
   * is unclear.
   */
  @Deprecated
  @Override
  default String[] getUrls(@NonNull OrderRootType rootType) {
    return getRootUrls(rootType);
  }
}