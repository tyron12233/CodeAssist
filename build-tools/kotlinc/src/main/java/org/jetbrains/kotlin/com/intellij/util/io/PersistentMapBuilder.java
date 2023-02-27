package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.ConcurrencyUtil;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/**
 * A builder helper for {@link PersistentHashMap}
 *
 * @see PersistentHashMap
 */
public final class PersistentMapBuilder<Key, Value> {
  private final @NonNull Path myFile;
  private final @NonNull KeyDescriptor<Key> myKeyDescriptor;
  private final @NonNull DataExternalizer<Value> myValueExternalizer;

  private Integer myInitialSize;
  private Integer myVersion;
  private StorageLockContext myLockContext;
  private Boolean myInlineValues;
  private Boolean myIsReadOnly;
  private Boolean myHasChunks;
  private Boolean myCompactOnClose;
  private @NonNull ExecutorService myWalExecutor;
  private boolean myEnableWal;

  private PersistentMapBuilder(final @NonNull Path file,
                               final @NonNull KeyDescriptor<Key> keyDescriptor,
                               final @NonNull DataExternalizer<Value> valueExternalizer,
                               final Integer initialSize,
                               final Integer version,
                               final StorageLockContext lockContext,
                               final Boolean inlineValues,
                               final Boolean isReadOnly,
                               final Boolean hasChunks,
                               final Boolean compactOnClose,
                               final @NonNull ExecutorService walExecutor,
                               final boolean enableWal) {
    myFile = file;
    myKeyDescriptor = keyDescriptor;
    myValueExternalizer = valueExternalizer;
    myInitialSize = initialSize;
    myVersion = version;
    myLockContext = lockContext;
    myInlineValues = inlineValues;
    myIsReadOnly = isReadOnly;
    myHasChunks = hasChunks;
    myCompactOnClose = compactOnClose;
    myWalExecutor = walExecutor;
    myEnableWal = enableWal;
  }

  private PersistentMapBuilder(@NonNull Path file,
                               @NonNull KeyDescriptor<Key> keyDescriptor,
                               @NonNull DataExternalizer<Value> valueExternalizer) {
    this(file, keyDescriptor, valueExternalizer,
         null, null, null, null, null, null, null,
         ConcurrencyUtil.newSameThreadExecutorService(),
         false);
  }

  @NonNull
  public PersistentHashMap<Key, Value> build() throws IOException {
    return new PersistentHashMap<>(buildImplementation());
  }

  @NonNull
  public PersistentMapBase<Key, Value> buildImplementation() throws IOException {
    Boolean oldHasNoChunksValue = null;
    if (myHasChunks != null) {
      oldHasNoChunksValue = PersistentHashMapValueStorage.CreationTimeOptions.HAS_NO_CHUNKS.get();
      PersistentHashMapValueStorage.CreationTimeOptions.HAS_NO_CHUNKS.set(!myHasChunks);
    }
    Boolean previousReadOnly = PersistentHashMapValueStorage.CreationTimeOptions.READONLY.get();
    PersistentHashMapValueStorage.CreationTimeOptions.READONLY.set(myIsReadOnly);

    try {
      if (SystemProperties.getBooleanProperty("idea.use.in.memory.persistent.map", false)) {
        return new PersistentMapInMemory<>(this);
      }

      return new PersistentMapImpl<>(this);
    }
    finally {
      if (myHasChunks != null) {
        PersistentHashMapValueStorage.CreationTimeOptions.HAS_NO_CHUNKS.set(oldHasNoChunksValue);
      }
      PersistentHashMapValueStorage.CreationTimeOptions.READONLY.set(previousReadOnly);
    }
  }

  @NonNull
  public Path getFile() {
    return myFile;
  }

  @NonNull
  public KeyDescriptor<Key> getKeyDescriptor() {
    return myKeyDescriptor;
  }

  @NonNull
  public DataExternalizer<Value> getValueExternalizer() {
    return myValueExternalizer;
  }

  @NonNull
  public static <Key, Value> PersistentMapBuilder<Key, Value> newBuilder(@NonNull Path file,
                                                                         @NonNull KeyDescriptor<Key> keyDescriptor,
                                                                         @NonNull DataExternalizer<Value> valueExternalizer) {
    return new PersistentMapBuilder<>(file, keyDescriptor, valueExternalizer);
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> withInitialSize(int initialSize) {
    myInitialSize = initialSize;
    return this;
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> withVersion(int version) {
    myVersion = version;
    return this;
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> withReadonly(boolean readonly) {
    myIsReadOnly = readonly;
    return this;
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> readonly() {
    return withReadonly(true);
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> withWal(boolean enableWal) {
    myEnableWal = enableWal;
    return this;
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> withWalExecutor(@NonNull ExecutorService service) {
    myWalExecutor = service;
    return this;
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> inlineValues(boolean inlineValues) {
    if (inlineValues && !(myValueExternalizer instanceof IntInlineKeyDescriptor)) {
      throw new IllegalStateException("can't inline values for externalizer " + myValueExternalizer.getClass());
    }
    myInlineValues = inlineValues;
    return this;
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> inlineValues() {
    return inlineValues(true);
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> withStorageLockContext(@Nullable StorageLockContext context) {
    myLockContext = context;
    return this;
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> hasChunks(boolean hasChunks) {
    myHasChunks = hasChunks;
    return this;
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> hasNoChunks() {
    myHasChunks = false;
    return this;
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> withCompactOnClose(boolean compactOnClose) {
    myCompactOnClose = compactOnClose;
    return this;
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> compactOnClose() {
    return withCompactOnClose(true);
  }

  public int getInitialSize(int defaultValue) {
      if (myInitialSize != null) {
          return myInitialSize;
      }
    return defaultValue;
  }

  public int getVersion(int defaultValue) {
      if (myVersion != null) {
          return myVersion;
      }
    return defaultValue;
  }

  public boolean getInlineValues(boolean defaultValue) {
      if (myInlineValues != null) {
          return myInlineValues;
      }
    return defaultValue;
  }

  public boolean getReadOnly(boolean defaultValue) {
      if (myIsReadOnly != null) {
          return myIsReadOnly;
      }
    return defaultValue;
  }

  public boolean getCompactOnClose(boolean defaultCompactOnClose) {
      if (myCompactOnClose != null) {
          return myCompactOnClose;
      }
    return defaultCompactOnClose;
  }

  public boolean isEnableWal() {
    return myEnableWal;
  }

  @NonNull
  public ExecutorService getWalExecutor() {
    return myWalExecutor;
  }

  @Nullable
  public StorageLockContext getLockContext() {
    return myLockContext;
  }

  /**
   * Since builder is not immutable, it is quite useful to have defensive copy of it
   *
   * @return shallow copy of this builder.
   */
  public PersistentMapBuilder<Key, Value> copy() {
    return new PersistentMapBuilder<>(
      myFile, myKeyDescriptor, myValueExternalizer,
      myInitialSize, myVersion, myLockContext, myInlineValues, myIsReadOnly, myHasChunks, myCompactOnClose,
      myWalExecutor, myEnableWal
    );
  }

  @NonNull
  public PersistentMapBuilder<Key, Value> copyWithFile(final @NonNull Path file) {
    return new PersistentMapBuilder<>(
      file, myKeyDescriptor, myValueExternalizer,
      myInitialSize, myVersion, myLockContext, myInlineValues, myIsReadOnly, myHasChunks, myCompactOnClose,
      myWalExecutor, myEnableWal
    );
  }
}