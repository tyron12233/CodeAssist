package org.jetbrains.kotlin.com.intellij.util.indexing.storage;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;

/**
 * A main interface to override index storages.
 * Use {@link FileBasedIndexLayoutProviderBean} to register a plugin which could provide custom index storage.
 */
@ApiStatus.Internal
public interface FileBasedIndexLayoutProvider {
  ExtensionPointName<FileBasedIndexLayoutProviderBean> STORAGE_LAYOUT_EP_NAME
    = ExtensionPointName.create("com.intellij.fileBasedIndexLayout");

  /**
   * @return storages required to realize IJ file-based indexes.
   */
  @NotNull
  <K, V> VfsAwareIndexStorageLayout<K, V> getLayout(@NotNull FileBasedIndexExtension<K, V> extension);

  default boolean isSupported() {
    return true;
  }
}