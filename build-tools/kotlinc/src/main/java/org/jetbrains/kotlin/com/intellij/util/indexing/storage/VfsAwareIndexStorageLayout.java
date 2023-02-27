package org.jetbrains.kotlin.com.intellij.util.indexing.storage;

import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexStorageLayout;

import java.io.IOException;

public interface VfsAwareIndexStorageLayout<Key, Value> extends IndexStorageLayout<Key, Value> {
  default @Nullable SnapshotInputMappingIndex<Key, Value, FileContent> createOrClearSnapshotInputMappings() throws IOException {
    return null;
  }

  void clearIndexData();
}