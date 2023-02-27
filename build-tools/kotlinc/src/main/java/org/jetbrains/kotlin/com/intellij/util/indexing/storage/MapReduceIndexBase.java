package org.jetbrains.kotlin.com.intellij.util.indexing.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.ConcurrencyUtil;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.*;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.AbstractUpdateData;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.MapReduceIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.AbstractMapForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndexAccessor;


import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.locks.Lock;

//@ApiStatus.Experimental
//@ApiStatus.Internal
public abstract class MapReduceIndexBase<Key, Value, FileCache> extends MapReduceIndex<Key, Value, FileContent>
  implements UpdatableIndex<Key, Value, FileContent, FileCache> {
  private final boolean mySingleEntryIndex;

  protected MapReduceIndexBase(@NonNull IndexExtension<Key, Value, FileContent> extension,
                               @NonNull ThrowableComputable<? extends IndexStorage<Key, Value>, ? extends IOException> storage,
                               @Nullable ThrowableComputable<? extends ForwardIndex, ? extends IOException> forwardIndex,
                               @Nullable ForwardIndexAccessor<Key, Value> forwardIndexAccessor) throws IOException {
    super(extension, storage, forwardIndex, forwardIndexAccessor);
    if (!(myIndexId instanceof ID<?, ?>)) {
      throw new IllegalArgumentException("myIndexId should be instance of com.intellij.util.indexing.ID");
    }
    mySingleEntryIndex = extension instanceof SingleEntryFileBasedIndexExtension;
  }

  @Override
  public boolean processAllKeys(@NonNull Processor<? super Key> processor, @NonNull GlobalSearchScope scope, @Nullable IdFilter idFilter) throws StorageException {
    Lock lock = getLock().readLock();
    lock.lock();
    try {
      return ((VfsAwareIndexStorage<Key, Value>)myStorage).processKeys(processor, scope, idFilter);
    } finally {
      lock.unlock();
    }
  }

  @NonNull
  @Override
  public Map<Key, Value> getIndexedFileData(int fileId) throws StorageException {
    Lock lock = getLock().readLock();
    lock.lock();
    try {
      Map<Key, Value> nullableIndexedData = getNullableIndexedData(fileId);
      if (nullableIndexedData == null ){
        nullableIndexedData = Collections.emptyMap();
      }
      return Collections.unmodifiableMap(nullableIndexedData);
    } catch (IOException e) {
      throw new StorageException(e);
    } finally {
      lock.unlock();
    }
  }

  @Nullable
  protected Map<Key, Value> getNullableIndexedData(int fileId) throws IOException, StorageException {
    if (isDisposed()) {
      return null;
    }
    // in future we will get rid of forward index for SingleEntryFileBasedIndexExtension
    if (mySingleEntryIndex) {
      @SuppressWarnings("unchecked")
      Key key = (Key)(Object)fileId;
      Ref<Map<Key, Value>> result = new Ref<>(Collections.emptyMap());
      ValueContainer<Value> container = getData(key);
      container.forEach((id, value) -> {
        boolean acceptNullValues = ((SingleEntryIndexer<?>)myIndexer).isAcceptNullValues();
        if (value != null || acceptNullValues) {
          result.set(Collections.singletonMap(key, value));
        }
        return false;
      });
      return result.get();
    }
    if (getForwardIndexAccessor() instanceof AbstractMapForwardIndexAccessor) {
      AbstractMapForwardIndexAccessor<Key, Value, ?> forwardIndexAccessor =
              (AbstractMapForwardIndexAccessor<Key, Value, ?>) getForwardIndexAccessor();
      ByteArraySequence serializedInputData = getForwardIndex().get(fileId);
      return forwardIndexAccessor.convertToInputDataMap(fileId, serializedInputData);
    }
    getLogger().error("Can't fetch indexed data for index " + myIndexId.getName());
    return null;
  }

  @Override
  public void checkCanceled() {
    ProgressManager.checkCanceled();
  }

  @Override
  public void updateWithMap(@NonNull AbstractUpdateData<Key, Value> updateData) throws StorageException {
    try {
      super.updateWithMap(updateData);
    }
    catch (ProcessCanceledException e) {
      getLogger().error("ProcessCancelledException is not expected here!", e);
      throw e;
    }
  }

  @Override
  public void setBufferingEnabled(boolean enabled) {
    // TODO to be removed
    throw new UnsupportedOperationException();
  }

  @Override
  public void cleanupMemoryStorage() {
    // TODO to be removed
    throw new UnsupportedOperationException();
  }

  @Override
  public void cleanupForNextTest() {
    // TODO to be removed
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeTransientDataForFile(int inputId) {
    // TODO to be removed
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeTransientDataForKeys(int inputId,
                                         @NonNull InputDataDiffBuilder<Key, Value> diffBuilder) {
    // TODO to be removed
    throw new UnsupportedOperationException();
  }

  protected abstract Logger getLogger();
}