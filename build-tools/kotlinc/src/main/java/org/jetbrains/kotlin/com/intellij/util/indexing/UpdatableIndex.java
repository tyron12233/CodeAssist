package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.AbstractUpdateData;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;

import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;

public interface UpdatableIndex<Key, Value, Input, FileIndexMetaData> extends InvertedIndex<Key, Value, Input>{

  boolean processAllKeys(@NonNull Processor<? super Key> processor, @NonNull GlobalSearchScope scope, @Nullable IdFilter idFilter) throws
                                                                                                                                   StorageException;

  @NonNull
  ReadWriteLock getLock();

  @NonNull
  Map<Key, Value> getIndexedFileData(int fileId) throws StorageException;

  /**
   * Goal of {@code getFileIndexMetaData()} is to allow
   * saving important data to a cache to use later without read lock in analog of {@link UpdatableIndex#setIndexedStateForFile(int, IndexedFile)}
   */
  @Nullable FileIndexMetaData getFileIndexMetaData(@NonNull IndexedFile file);

  void setIndexedStateForFileOnFileIndexMetaData(int fileId, @Nullable FileIndexMetaData data);

  void setIndexedStateForFile(int fileId, @NonNull IndexedFile file);

  void invalidateIndexedStateForFile(int fileId);

  void setUnindexedStateForFile(int fileId);

  @NonNull
  FileIndexingState getIndexingStateForFile(int fileId, @NonNull IndexedFile file);

  long getModificationStamp();

  void removeTransientDataForFile(int inputId);

  void removeTransientDataForKeys(int inputId, @NonNull InputDataDiffBuilder<Key, Value> diffBuilder);

  @NonNull
  IndexExtension<Key, Value, Input> getExtension();

  void updateWithMap(@NonNull AbstractUpdateData<Key, Value> updateData) throws StorageException;

  void setBufferingEnabled(boolean enabled);

  void cleanupMemoryStorage();

  @VisibleForTesting
  void cleanupForNextTest();
}