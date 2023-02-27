package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;

import java.io.IOException;

public interface CustomImplementationFileBasedIndexExtension<K, V> {
  @NonNull
  UpdatableIndex<K, V, FileContent, ?> createIndexImplementation(@NonNull FileBasedIndexExtension<K, V> extension,
                                                                 @NonNull VfsAwareIndexStorageLayout<K, V> indexStorageLayout)
    throws StorageException, IOException;

  default void handleInitializationError(@NonNull Throwable e) { }
}