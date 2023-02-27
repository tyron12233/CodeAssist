package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntList;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

class StripedIndexingStampLock {
  /**
   * This value is used when there is no hash for provided id. 'Real' hashes are never equal to it.
   */
  static final long NON_EXISTENT_HASH = 0L;
  private static final int LOCK_SIZE = 16;
  private final ReadWriteLock[] myLocks = new ReadWriteLock[LOCK_SIZE];
  private final Int2ObjectOpenHashMap<Long>[] myHashes = new Int2ObjectOpenHashMap[LOCK_SIZE];
  private final AtomicLong myCurrentHash = new AtomicLong(NON_EXISTENT_HASH + 1);

  StripedIndexingStampLock() {
    for (int i = 0; i < myLocks.length; ++i) {
      myLocks[i] = new ReentrantReadWriteLock();
      myHashes[i] = new Int2ObjectOpenHashMap<Long>();
    }
  }

  /**
   * @return NON_EXISTENT_HASH if there was no hash for this id,
   * and hash (>0) if there was one
   */
  long releaseHash(int id) {
    Lock lock = getLock(id).writeLock();
    lock.lock();
    try {
      return getHashes(id).remove(id);
    }
    finally {
      lock.unlock();
    }
  }

  long getHash(int id) {
    Lock lock = getLock(id).writeLock();
    lock.lock();
    try {
      Int2ObjectMap<Long> hashes = getHashes(id);
      long hash = hashes.get(id);
      if (hash == NON_EXISTENT_HASH) {
        hash = myCurrentHash.getAndIncrement();
        hashes.put(id, ((Long) hash));
      }
      return hash;
    }
    finally {
      lock.unlock();
    }
  }

  private ReadWriteLock getLock(int fileId) {
    return myLocks[getIndex(fileId)];
  }

  private int getIndex(int fileId) {
      if (fileId < 0) {
          fileId = -fileId;
      }
    return (fileId & 0xFF) % myLocks.length;
  }

  private Int2ObjectOpenHashMap<Long> getHashes(int fileId) {
    return myHashes[getIndex(fileId)];
  }

  void clear() {
    forEachStripe(false, Int2ObjectMap::clear);
  }

  int[] dumpIds() {
    IntList result = new IntArrayList();
    forEachStripe(true, (Int2ObjectMap<Long> map) -> {
      result.addAll(map.keySet());
    });
    return result.toIntArray();
  }

  private void forEachStripe(boolean readLock, Consumer<? super Int2ObjectMap<Long>> consumer) {
    List<Exception> exceptions = new SmartList<>();
    for (int i = 0; i < myLocks.length; i++) {
      Lock lock = null;
      try {
        ReadWriteLock readWriteLock = getLock(i);
        lock = readLock ? readWriteLock.readLock() : readWriteLock.writeLock();
        lock.lock();
        consumer.accept(getHashes(i));
      }
      catch (Exception e) {
        exceptions.add(e);
      }
      finally {
        if (lock != null) {
          try {
            lock.unlock();
          }
          catch (Exception e) {
            exceptions.add(e);
          }
        }
      }
    }
    if (!exceptions.isEmpty()) {
      IllegalStateException exception = new IllegalStateException("Exceptions while clearing");
      for (Exception suppressed : exceptions) {
        exception.addSuppressed(suppressed);
      }
      throw exception;
    }
  }
}