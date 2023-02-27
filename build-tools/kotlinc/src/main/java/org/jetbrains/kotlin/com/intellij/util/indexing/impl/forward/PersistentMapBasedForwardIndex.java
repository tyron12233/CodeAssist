package org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.io.ByteSequenceDataExternalizer;
import org.jetbrains.kotlin.com.intellij.util.io.EnumeratorIntegerDescriptor;
import org.jetbrains.kotlin.com.intellij.util.io.MeasurableIndexStore;
import org.jetbrains.kotlin.com.intellij.util.io.PagedFileStorage;
import org.jetbrains.kotlin.com.intellij.util.io.PersistentMap;
import org.jetbrains.kotlin.com.intellij.util.io.PersistentMapBuilder;
import org.jetbrains.kotlin.com.intellij.util.io.StorageLockContext;

import java.io.IOException;
import java.nio.file.Path;

public class PersistentMapBasedForwardIndex implements ForwardIndex, MeasurableIndexStore {
  private volatile @NotNull PersistentMap<Integer, ByteArraySequence> myPersistentMap;
  private final @NotNull Path myMapFile;
  private final boolean myUseChunks;
  private final boolean myReadOnly;
  private final @Nullable StorageLockContext myStorageLockContext;

  public PersistentMapBasedForwardIndex(@NotNull Path mapFile, boolean isReadOnly) throws IOException {
    this(mapFile, true, isReadOnly, null);
  }

  public PersistentMapBasedForwardIndex(@NotNull Path mapFile,
                                        boolean useChunks,
                                        boolean isReadOnly,
                                        @Nullable StorageLockContext storageLockContext) throws IOException {
    myPersistentMap = createMap(mapFile, useChunks, isReadOnly, storageLockContext);
    myStorageLockContext = storageLockContext;
    myMapFile = mapFile;
    myUseChunks = useChunks;
    myReadOnly = isReadOnly;
  }

  @Nullable
  @Override
  public ByteArraySequence get(@NotNull Integer key) throws IOException {
    return myPersistentMap.get(key);
  }

  @Override
  public void put(@NotNull Integer key, @Nullable ByteArraySequence value) throws IOException {
    if (value == null) {
      myPersistentMap.remove(key);
    }
    else {
      myPersistentMap.put(key, value);
    }
  }

  @Override
  public void force() {
    myPersistentMap.force();
  }

  @Override
  public int keysCountApproximately() {
    return MeasurableIndexStore.keysCountApproximatelyIfPossible(myPersistentMap);
  }

  @Override
  public void clear() throws IOException {
    myPersistentMap.closeAndClean();
    myPersistentMap = createMap(myMapFile, myUseChunks, myReadOnly, myStorageLockContext);
  }

  @Override
  public void close() throws IOException {
    myPersistentMap.close();
  }

  public boolean containsMapping(int key) throws IOException {
    return myPersistentMap.containsMapping(key);
  }

  public PersistentMap<Integer, ByteArraySequence> getUnderlyingMap(){
    return myPersistentMap;
  }

  @NotNull
  private static PersistentMap<Integer, ByteArraySequence> createMap(@NotNull Path file,
                                                                     boolean useChunks,
                                                                     boolean isReadOnly,
                                                                     @Nullable StorageLockContext storageLockContext) throws IOException {
    assert PagedFileStorage.THREAD_LOCAL_STORAGE_LOCK_CONTEXT.get() == null || storageLockContext == null;
    PagedFileStorage.THREAD_LOCAL_STORAGE_LOCK_CONTEXT.set(storageLockContext);
    try {
      return PersistentMapBuilder
        .newBuilder(file, EnumeratorIntegerDescriptor.INSTANCE, ByteSequenceDataExternalizer.INSTANCE)
        .hasChunks(useChunks)
        .withReadonly(isReadOnly)
        .build();
    }
    finally {
      PagedFileStorage.THREAD_LOCAL_STORAGE_LOCK_CONTEXT.remove();
    }
  }
}