package org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.concurrency.ConcurrentCollectionFactory;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentIntObjectHashMap;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentIntObjectMap;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.DirectInputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexDebugProperties;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputData;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.SnapshotInputMappingIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransientFileContentIndex<Key, Value, FileCachedData extends VfsAwareMapReduceIndex.IndexerIdHolder>
  extends VfsAwareMapReduceIndex<Key, Value, FileCachedData> {
  private static final Logger LOG = Logger.getInstance(TransientFileContentIndex.class);

  private final AtomicBoolean myInMemoryMode = new AtomicBoolean();
  private final ConcurrentIntObjectMap<Map<Key, Value>> myInMemoryKeysAndValues =
    ConcurrentCollectionFactory.createConcurrentIntObjectMap();


  public TransientFileContentIndex(@NonNull FileBasedIndexExtension<Key, Value> extension,
                                   @NonNull VfsAwareIndexStorageLayout<Key, Value> indexStorageLayout)
    throws IOException {
    super(extension,
          new VfsAwareIndexStorageLayout<Key, Value>() {
            @Override
            public @NonNull IndexStorage<Key, Value> openIndexStorage() throws IOException {
              return new TransientChangesIndexStorage<>(indexStorageLayout.openIndexStorage(), extension);
            }

            @Override
            public @Nullable SnapshotInputMappingIndex<Key, Value, FileContent> createOrClearSnapshotInputMappings() throws IOException {
              return indexStorageLayout.createOrClearSnapshotInputMappings();
            }

            @Override
            public void clearIndexData() {
              indexStorageLayout.clearIndexData();
            }

            @Override
            public @Nullable ForwardIndex openForwardIndex() throws IOException {
              return indexStorageLayout.openForwardIndex();
            }

            @Override
            public @Nullable ForwardIndexAccessor<Key, Value> getForwardIndexAccessor() throws IOException {
              return indexStorageLayout.getForwardIndexAccessor();
            }
          });
    installMemoryModeListener();
  }

  @NonNull
  @Override
  protected InputDataDiffBuilder<Key, Value> getKeysDiffBuilder(int inputId) throws IOException {
    if (myInMemoryMode.get()) {
      Map<Key, Value> keysAndValues = myInMemoryKeysAndValues.get(inputId);
      if (keysAndValues != null) {
        return getKeysDiffBuilder(inputId, keysAndValues);
      }
    }
    return super.getKeysDiffBuilder(inputId);
  }

  @Override
  protected void updateForwardIndex(int inputId, @NonNull InputData<Key, Value> data) throws IOException {
    if (myInMemoryMode.get()) {
      myInMemoryKeysAndValues.put(inputId, data.getKeyValues());
    }
    else {
      super.updateForwardIndex(inputId, data);
    }
  }

  @Override
  @Nullable
  protected Map<Key, Value> getNullableIndexedData(int fileId) throws IOException, StorageException {
    if (myInMemoryMode.get()) {
      Map<Key, Value> map = myInMemoryKeysAndValues.get(fileId);
        if (map != null) {
            return map;
        }
    }
    return super.getNullableIndexedData(fileId);
  }

  private void installMemoryModeListener() {
    IndexStorage<Key, Value> storage = getStorage();
    if (storage instanceof TransientChangesIndexStorage) {
      ((TransientChangesIndexStorage<Key, Value>)storage).addBufferingStateListener(
        new TransientChangesIndexStorage.BufferingStateListener() {
          @Override
          public void bufferingStateChanged(boolean newState) {
            myInMemoryMode.set(newState);
          }

          @SuppressWarnings("deprecation")
          @Override
          public void memoryStorageCleared() {
            ((ConcurrentIntObjectHashMap<?>) myInMemoryKeysAndValues).clear();
          }
        });
    }
  }

  @Override
  public void setBufferingEnabled(boolean enabled) {
    ((TransientChangesIndexStorage<Key, Value>)getStorage()).setBufferingEnabled(enabled);
  }

  @Override
  public void removeTransientDataForFile(int inputId) {
    if (IndexDebugProperties.DEBUG) {
      LOG.assertTrue(ProgressManager.getInstance().isInNonCancelableSection());
    }
    getLock().writeLock().lock();
    try {
      Map<Key, Value> keyValueMap = myInMemoryKeysAndValues.remove(inputId);
        if (keyValueMap == null) {
            return;
        }

      try {
        removeTransientDataForInMemoryKeys(inputId, keyValueMap);
        InputDataDiffBuilder<Key, Value> builder = getKeysDiffBuilder(inputId);
        removeTransientDataForKeys(inputId, builder);
      }
      catch (IOException throwable) {
        throw new RuntimeException(throwable);
      }
    }
    finally {
      getLock().writeLock().unlock();
    }
  }

  protected void removeTransientDataForInMemoryKeys(int inputId, @NonNull Map<Key, Value> map) throws IOException {
    removeTransientDataForKeys(inputId, getKeysDiffBuilder(inputId, map));
  }

  @Override
  public void removeTransientDataForKeys(int inputId, @NonNull InputDataDiffBuilder<Key, Value> diffBuilder) {
    TransientChangesIndexStorage<Key, Value> memoryIndexStorage = (TransientChangesIndexStorage<Key, Value>)getStorage();
    boolean modified = false;
    for (Key key : ((DirectInputDataDiffBuilder<Key, Value>)diffBuilder).getKeys()) {
      if (memoryIndexStorage.clearMemoryMapForId(key, inputId) && !modified) {
        modified = true;
      }
    }
    if (modified) {
      incrementModificationStamp();
    }
  }


  @Override
  public void cleanupMemoryStorage() {
    TransientChangesIndexStorage<Key, Value> memStorage = (TransientChangesIndexStorage<Key, Value>)getStorage();
    //no synchronization on index write-lock, should be performed fast as possible since executed in write-action
    if (memStorage.clearMemoryMap()) {
      incrementModificationStamp();
    }
    memStorage.fireMemoryStorageCleared();
  }

  @Override
  public void cleanupForNextTest() {
    IndexStorage<Key, Value> memStorage = getStorage();
    getLock().readLock().lock();
    try {
      memStorage.clearCaches();
    } finally {
      getLock().readLock().unlock();
    }
  }

  public static <Key, Value> TransientFileContentIndex<Key, Value, VfsAwareMapReduceIndex.IndexerIdHolder> createIndex(@NonNull FileBasedIndexExtension<Key, Value> extension,
                                                                                                                       @NonNull VfsAwareIndexStorageLayout<Key, Value> indexStorageLayout)
    throws IOException {
    return new TransientFileContentIndex<>(extension, indexStorageLayout);
  }
}