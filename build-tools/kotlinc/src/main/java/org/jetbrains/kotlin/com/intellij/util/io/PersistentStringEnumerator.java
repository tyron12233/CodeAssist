package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.file.Path;

public class PersistentStringEnumerator extends PersistentEnumerator<String> implements AbstractStringEnumerator {
  @Nullable private final CachingEnumerator<String> myCache;

  public PersistentStringEnumerator(@NonNull Path file) throws IOException {
    this(file, null);
  }

  public PersistentStringEnumerator(@NonNull Path file, @Nullable StorageLockContext storageLockContext) throws IOException {
    this(file, 1024 * 4, storageLockContext);
  }

  public PersistentStringEnumerator(@NonNull Path file, boolean cacheLastMappings) throws IOException {
    this(file, 1024 * 4, cacheLastMappings, null);
  }

  public PersistentStringEnumerator(@NonNull Path file, final int initialSize) throws IOException {
    this(file, initialSize, null);
  }

  public PersistentStringEnumerator(@NonNull Path file,
                                    final int initialSize,
                                    @Nullable StorageLockContext lockContext) throws IOException {
    this(file, initialSize, false, lockContext);
  }

  public PersistentStringEnumerator(@NonNull Path file,
                                     final int initialSize,
                                     boolean cacheLastMappings,
                                     @Nullable StorageLockContext lockContext) throws IOException {
    super(file, EnumeratorStringDescriptor.INSTANCE, initialSize, lockContext);
    myCache = cacheLastMappings ? new CachingEnumerator<>(new DataEnumerator<String>() {
      @Override
      public int enumerate(@Nullable String value) throws IOException {
        return PersistentStringEnumerator.super.enumerate(value);
      }

      @Nullable
      @Override
      public String valueOf(int idx) throws IOException {
        return PersistentStringEnumerator.super.valueOf(idx);
      }
    }, EnumeratorStringDescriptor.INSTANCE) : null;
  }

  @Override
  public int enumerate(@Nullable String value) throws IOException {
    return myCache != null ? myCache.enumerate(value) : super.enumerate(value);
  }

  @Nullable
  @Override
  public String valueOf(int idx) throws IOException {
    return myCache != null ? myCache.valueOf(idx) : super.valueOf(idx);
  }

  @Override
  public void close() throws IOException {
    super.close();
      if (myCache != null) {
          myCache.close();
      }
  }
}
