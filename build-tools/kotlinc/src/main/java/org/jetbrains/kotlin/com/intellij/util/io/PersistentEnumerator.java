package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.Forceable;
import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.locks.Lock;

public class PersistentEnumerator<Data> implements ScannableDataEnumeratorEx<Data>, Closeable, Forceable {
  @NonNull
  protected final PersistentEnumeratorBase<Data> myEnumerator;

  public PersistentEnumerator(@NonNull Path file, @NonNull KeyDescriptor<Data> dataDescriptor, final int initialSize) throws IOException {
    this(file, dataDescriptor, initialSize, null);
  }

  public PersistentEnumerator(@NonNull final Path file,
                              @NonNull KeyDescriptor<Data> dataDescriptor,
                              final int initialSize,
                              @Nullable StorageLockContext lockContext) throws IOException {
    myEnumerator = new PersistentBTreeEnumerator<>(file, dataDescriptor, initialSize, lockContext);
  }

  public PersistentEnumerator(@NonNull final File file,
                              @NonNull KeyDescriptor<Data> dataDescriptor,
                              final int initialSize,
                              @Nullable StorageLockContext lockContext,
                              int version) throws IOException {
    this(file.toPath(), dataDescriptor, initialSize, lockContext, version);
  }

  public PersistentEnumerator(@NonNull Path file,
                              @NonNull KeyDescriptor<Data> dataDescriptor,
                              final int initialSize,
                              @Nullable StorageLockContext lockContext,
                              int version) throws IOException {
    myEnumerator = createDefaultEnumerator(file, dataDescriptor, initialSize, lockContext, version, true);
  }

  @NonNull
  static <Data> PersistentEnumeratorBase<Data> createDefaultEnumerator(@NonNull Path file,
                                                                       @NonNull KeyDescriptor<Data> dataDescriptor,
                                                                       final int initialSize,
                                                                       @Nullable StorageLockContext lockContext,
                                                                       int version,
                                                                       boolean registerForStats) throws IOException {
    return new PersistentBTreeEnumerator<>(file, dataDescriptor, initialSize, lockContext, version, false, registerForStats);
  }

  public static int getVersion() {
    return PersistentBTreeEnumerator.baseVersion();
  }

  @Override
  public void close() throws IOException {
    final PersistentEnumeratorBase<Data> enumerator = myEnumerator;
    //noinspection ConstantConditions
    if (enumerator != null) {
      enumerator.close();
    }
  }

  public boolean isClosed() {
    return myEnumerator.isClosed();
  }

  @Override
  public boolean isDirty() {
    return myEnumerator.isDirty();
  }

  public final void markDirty() throws IOException {
    Lock lock = myEnumerator.getWriteLock();
    lock.lock();
    try {
      myEnumerator.markDirty(true);
    }
    finally {
      lock.unlock();
    }
  }

  public boolean isCorrupted() {
    return myEnumerator.isCorrupted();
  }

  public void markCorrupted() {
    myEnumerator.markCorrupted();
  }

  @Override
  public void force() {
    myEnumerator.force();
  }

  @Override
  public Data valueOf(int id) throws IOException {
    return myEnumerator.valueOf(id);
  }

  @Override
  public int enumerate(Data name) throws IOException {
    return myEnumerator.enumerate(name);
  }

  @Override
  public int tryEnumerate(Data name) throws IOException {
    return myEnumerator.tryEnumerate(name);
  }


  public Collection<Data> getAllDataObjects(@Nullable final PersistentEnumeratorBase.DataFilter filter) throws IOException {
    return myEnumerator.getAllDataObjects(filter);
  }

  @Override
  public boolean processAllDataObjects(@NonNull Processor<? super Data> processor) throws IOException {
    return myEnumerator.iterateData(processor);
  }
}