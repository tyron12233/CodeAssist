package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.openapi.util.LowMemoryWatcher;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.util.indexing.*;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.IntForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.IntForwardIndexAccessor;
import org.jetbrains.kotlin.com.intellij.util.io.MeasurableIndexStore;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.jetbrains.kotlin.com.intellij.util.io.MeasurableIndexStore.keysCountApproximatelyIfPossible;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public abstract class MapReduceIndex<Key,Value, Input> implements InvertedIndex<Key, Value, Input>,
                                                                  MeasurableIndexStore {
  private static final Logger LOG = Logger.getInstance(MapReduceIndex.class);

  protected final IndexId<Key, Value> myIndexId;
  protected final IndexStorage<Key, Value> myStorage;
  protected final AtomicLong myModificationStamp = new AtomicLong();
  protected final DataIndexer<Key, Value, Input> myIndexer;

  private final @Nullable ValueSerializationChecker<Value, Input> myValueSerializationChecker;
  private final IndexExtension<Key, Value, Input> myExtension;
  private final @Nullable ForwardIndex myForwardIndex;
  private final ForwardIndexAccessor<Key, Value> myForwardIndexAccessor;
  private final ReadWriteLock myLock;
  private final boolean myUseIntForwardIndex;
  private volatile boolean myDisposed;
  private final LowMemoryWatcher myLowMemoryFlusher;

  protected MapReduceIndex(@NonNull IndexExtension<Key, Value, Input> extension,
                           @NonNull IndexStorageLayout<Key, Value> indexStorageLayout) throws IOException {
    this(extension,
         indexStorageLayout.openIndexStorage(),
         indexStorageLayout.openForwardIndex(),
         indexStorageLayout.getForwardIndexAccessor());
  }

  protected MapReduceIndex(@NonNull IndexExtension<Key, Value, Input> extension,
                           @NonNull IndexStorage<Key, Value> storage,
                           @Nullable ForwardIndex forwardIndex,
                           @Nullable ForwardIndexAccessor<Key, Value> forwardIndexAccessor) throws IOException {
    this(extension, () -> storage, () -> forwardIndex, forwardIndexAccessor);
  }

  protected MapReduceIndex(@NonNull IndexExtension<Key, Value, Input> extension,
                           @NonNull ThrowableComputable<? extends IndexStorage<Key, Value>, ? extends IOException> storage,
                           @Nullable ThrowableComputable<? extends ForwardIndex, ? extends IOException> forwardIndex,
                           @Nullable ForwardIndexAccessor<Key, Value> forwardIndexAccessor) throws IOException {
    myIndexId = extension.getName();
    myExtension = extension;
    myIndexer = myExtension.getIndexer();
    myStorage = storage.compute();
    try {
      myForwardIndex = forwardIndex == null ? null : forwardIndex.compute();
    }
    catch (IOException e) {
      tryDispose();
      throw e;
    }
    myForwardIndexAccessor = forwardIndexAccessor;
    myUseIntForwardIndex = myForwardIndex instanceof IntForwardIndex && myForwardIndexAccessor instanceof IntForwardIndexAccessor;
    LOG.assertTrue(myForwardIndex instanceof IntForwardIndex == myForwardIndexAccessor instanceof IntForwardIndexAccessor,
                   "Invalid index configuration for " + myIndexId);
    myLock = new ReentrantReadWriteLock();
    myValueSerializationChecker = new ValueSerializationChecker<>(extension, getSerializationProblemReporter());
    myLowMemoryFlusher = LowMemoryWatcher.register(this::clearCaches);
  }

  private void clearCaches() {
    try {
      myLock.readLock().lock();
      try {
        myStorage.clearCaches();
      }
      finally {
        myLock.readLock().unlock();
      }

      flush();
    }
    catch (Throwable e) {
      requestRebuild(e);
    }
  }

  protected @NonNull ValueSerializationProblemReporter getSerializationProblemReporter() {
    return ValueSerializationChecker.DEFAULT_SERIALIZATION_PROBLEM_REPORTER;
  }

  protected void tryDispose() {
    try {
      dispose();
    }
    catch (Exception e) {
      LOG.info(e);
    }
  }

  public @Nullable ForwardIndex getForwardIndex() {
    return myForwardIndex;
  }

  public ForwardIndexAccessor<Key, Value> getForwardIndexAccessor() {
    return myForwardIndexAccessor;
  }

  public @NonNull IndexExtension<Key, Value, Input> getExtension() {
    return myExtension;
  }

  public @NonNull IndexStorage<Key, Value> getStorage() {
    return myStorage;
  }

  public final @NonNull ReadWriteLock getLock() {
    return myLock;
  }

  @Override
  public void clear() {
    myLock.writeLock().lock();
    try {
      incrementModificationStamp();
      doClear();
    }
    catch (StorageException | IOException e) {
      LOG.info(e);
    }
    finally {
      myLock.writeLock().unlock();
    }
  }

  protected void doClear() throws StorageException, IOException {
    myStorage.clear();
      if (myForwardIndex != null) {
          myForwardIndex.clear();
      }
  }

  @Override
  public void flush() throws StorageException{
    myLock.readLock().lock();
    try {
      doFlush();
    }
    catch (IOException e) {
      throw new StorageException(e);
    }
    catch (RuntimeException e) {
      final Throwable cause = e.getCause();
      if (cause instanceof StorageException || cause instanceof IOException) {
        throw new StorageException(cause);
      }
      else {
        throw e;
      }
    }
    finally {
      myLock.readLock().unlock();
    }
  }

  protected void doFlush() throws IOException, StorageException {
      if (myForwardIndex != null) {
          myForwardIndex.force();
      }
    myStorage.flush();
  }

  @Override
  public void dispose() {
    myLowMemoryFlusher.stop();
    myLock.writeLock().lock();
    try {
      myDisposed = true;
      doDispose();
    }
    catch (StorageException e) {
      LOG.error(e);
    }
    finally {
      myLock.writeLock().unlock();
    }
  }

  @Override
  public int keysCountApproximately() {
    return keysCountApproximatelyIfPossible(myStorage);
  }

  protected boolean isDisposed() {
    return myDisposed;
  }

  protected void doDispose() throws StorageException {
    try {
      myStorage.close();
    }
    finally {
      try {
          if (myForwardIndex != null) {
              myForwardIndex.close();
          }
      }
      catch (IOException e) {
        LOG.error(e);
      }
    }
  }

  @Override
  public @NonNull ValueContainer<Value> getData(final @NonNull Key key) throws StorageException {
    myLock.readLock().lock();
    try {
      if (isDisposed()) {
        return new ValueContainerImpl<>();
      }
      IndexDebugProperties.DEBUG_INDEX_ID.set(myIndexId);
      return myStorage.read(key);
    }
    finally {
      IndexDebugProperties.DEBUG_INDEX_ID.set(null);
      myLock.readLock().unlock();
    }
  }

  @Override
  public @NonNull Computable<Boolean> mapInputAndPrepareUpdate(int inputId, @Nullable Input content)
    throws MapReduceIndexMappingException, ProcessCanceledException {
    InputData<Key, Value> data;
    try {
      data = mapInput(inputId, content);
    }
    catch (ProcessCanceledException | MapReduceIndexMappingException e) {
      throw e;
    }
    catch (Exception e) {
      throw new MapReduceIndexMappingException(e, myExtension.getClass());
    }

    return prepareUpdate(inputId, data);
  }

  @Override
  public @NonNull IndexUpdateComputable prepareUpdate(int inputId, @NonNull InputData<Key, Value> data) {
    UpdateData<Key, Value> updateData = new UpdateData<>(
      inputId,
      data.getKeyValues(),
      () -> getKeysDiffBuilder(inputId),
      myIndexId,
      () -> updateForwardIndex(inputId, data)
    );

    return new IndexUpdateComputable(updateData, data);
  }

  protected void checkNonCancellableSection() { }

  protected void updateForwardIndex(int inputId, @NonNull InputData<Key, Value> data) throws IOException {
    if (myForwardIndex != null) {
      if (myUseIntForwardIndex) {
        ((IntForwardIndex)myForwardIndex).putInt(inputId, ((IntForwardIndexAccessor<Key, Value>)myForwardIndexAccessor).serializeIndexedDataToInt(data));
      }
      else {
        myForwardIndex.put(inputId, myForwardIndexAccessor.serializeIndexedData(data));
      }
    }
  }

  protected @NonNull InputDataDiffBuilder<Key, Value> getKeysDiffBuilder(int inputId) throws IOException {
    if (myForwardIndex != null) {
      if (myUseIntForwardIndex) {
        return ((IntForwardIndexAccessor<Key, Value>)myForwardIndexAccessor).getDiffBuilderFromInt(inputId, ((IntForwardIndex)myForwardIndex).getInt(inputId));
      }
      else {
        return myForwardIndexAccessor.getDiffBuilder(inputId, myForwardIndex.get(inputId));
      }
    }
    return new EmptyInputDataDiffBuilder<>(inputId);
  }

  protected @NonNull InputData<Key, Value> mapInput(int inputId, @Nullable Input content) {
    if (content == null) {
      return InputData.empty();
    }
    Map<Key, Value> data = mapByIndexer(inputId,  content);
    if (myValueSerializationChecker != null) {
      myValueSerializationChecker.checkValueSerialization(data, content);
    }
    checkCanceled();
    return new InputData<>(data);
  }

  protected @NonNull Map<Key, Value> mapByIndexer(int inputId, @NonNull Input content) {
    return myIndexer.map(content);
  }

  public abstract void checkCanceled();

  protected abstract void requestRebuild(@NonNull Throwable e);

  public long getModificationStamp() {
    return myModificationStamp.get();
  }

  private final RemovedKeyProcessor<Key> myRemovedKeyProcessor = new RemovedKeyProcessor<Key>() {
    @Override
    public void process(Key key, int inputId) throws StorageException {
      incrementModificationStamp();
      myStorage.removeAllValues(key, inputId);
    }
  };

  private final KeyValueUpdateProcessor<Key, Value> myAddedKeyProcessor = new KeyValueUpdateProcessor<Key, Value>() {
    @Override
    public void process(Key key, Value value, int inputId) throws StorageException {
      incrementModificationStamp();
      myStorage.addValue(key, inputId, value);
    }
  };

  private final KeyValueUpdateProcessor<Key, Value> myUpdatedKeyProcessor = new KeyValueUpdateProcessor<Key, Value>() {
    @Override
    public void process(Key key, Value value, int inputId) throws StorageException {
      incrementModificationStamp();
      myStorage.updateValue(key, inputId, value);
    }
  };

  protected void incrementModificationStamp() {
    myModificationStamp.incrementAndGet();
  }

  public void updateWithMap(@NonNull AbstractUpdateData<Key, Value> updateData) throws StorageException {
    myLock.writeLock().lock();
    try {
      IndexId<?, ?> oldIndexId = IndexDebugProperties.DEBUG_INDEX_ID.get();
      try {
        IndexDebugProperties.DEBUG_INDEX_ID.set(myIndexId);
        boolean hasDifference = updateData.iterateKeys(myAddedKeyProcessor, myUpdatedKeyProcessor, myRemovedKeyProcessor);
          if (hasDifference) {
              updateData.updateForwardIndex();
          }
      }
      catch (ProcessCanceledException e) {
        LOG.error("ProcessCanceledException is not expected here!", e);
        throw e;
      }
      catch (Throwable e) { // e.g. IOException, AssertionError
        throw new StorageException(e);
      }
      finally {
        IndexDebugProperties.DEBUG_INDEX_ID.set(oldIndexId);
      }
    }
    finally {
      myLock.writeLock().unlock();
    }
  }

  public class IndexUpdateComputable implements Computable<Boolean> {
    private final UpdateData<Key, Value> myUpdateData;
    private final InputData<Key, Value> myInputData;

    private IndexUpdateComputable(@NonNull UpdateData<Key, Value> updateData, @NonNull InputData<Key, Value> inputData) {
      myUpdateData = updateData;
      myInputData = inputData;
    }

    public @NonNull InputData<Key, Value> getInputData() {
      return myInputData;
    }

    @Override
    public Boolean compute() {
      checkNonCancellableSection();
      try {
        MapReduceIndex.this.updateWithMap(myUpdateData);
      }
      catch (StorageException | ProcessCanceledException ex) {
        String message = "An exception during updateWithMap(). Index " + myIndexId.getName() + " will be rebuilt.";
        //noinspection InstanceofCatchParameter
        if (ex instanceof ProcessCanceledException) {
          LOG.error(message, ex);
        }
        else {
          if (IndexDebugProperties.IS_UNIT_TEST_MODE) {
            LOG.error(message, ex);
          }
          else {
            LOG.info(message, ex);
          }
        }
        MapReduceIndex.this.requestRebuild(ex);
        return false;
      }
      return true;
    }
  }
}