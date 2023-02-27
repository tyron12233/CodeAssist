package org.jetbrains.kotlin.com.intellij.util.io;

import static org.jetbrains.kotlin.com.intellij.util.io.PageCacheUtils.FILE_PAGE_CACHE_NEW_CAPACITY_BYTES;
import static org.jetbrains.kotlin.com.intellij.util.io.PageCacheUtils.FILE_PAGE_CACHE_OLD_CAPACITY_BYTES;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexDebugProperties;

public final class StorageLockContext {

  private static final FilePageCache DEFAULT_FILE_PAGE_CACHE = new FilePageCache(FILE_PAGE_CACHE_OLD_CAPACITY_BYTES);
  @Nullable
  private static final FilePageCacheLockFree DEFAULT_FILE_PAGE_CACHE_NEW = PageCacheUtils.LOCK_FREE_VFS_ENABLED ?
                                                                           new FilePageCacheLockFree(FILE_PAGE_CACHE_NEW_CAPACITY_BYTES) :
                                                                           null;

  static final StorageLockContext ourDefaultContext = new StorageLockContext(false);

  private final ReentrantReadWriteLock myLock = new ReentrantReadWriteLock();

  @NonNull
  private final FilePageCache myFilePageCache;

  /** In general, null if {@link PageCacheUtils#LOCK_FREE_VFS_ENABLED} is false */
  @Nullable
  private final FilePageCacheLockFree myFilePageCacheLockFree;

  private final boolean myUseReadWriteLock;
  private final boolean myCacheChannels;
  private final boolean myDisableAssertions;

  @VisibleForTesting
  StorageLockContext(@NonNull FilePageCache filePageCache,
                     @Nullable FilePageCacheLockFree filePageCacheLockFree,
                     boolean useReadWriteLock,
                     boolean cacheChannels,
                     boolean disableAssertions) {
    myFilePageCache = filePageCache;
    myUseReadWriteLock = useReadWriteLock;
    myCacheChannels = cacheChannels;
    myDisableAssertions = disableAssertions;
    myFilePageCacheLockFree = filePageCacheLockFree;
  }

  @VisibleForTesting
  StorageLockContext(@Nullable FilePageCacheLockFree filePageCacheLockFree,
                     boolean useReadWriteLock,
                     boolean cacheChannels,
                     boolean disableAssertions) {
    this(DEFAULT_FILE_PAGE_CACHE, filePageCacheLockFree, useReadWriteLock, cacheChannels, disableAssertions);
  }

  public StorageLockContext(boolean useReadWriteLock,
                            boolean cacheChannels,
                            boolean disableAssertions) {
    this(DEFAULT_FILE_PAGE_CACHE,
         DEFAULT_FILE_PAGE_CACHE_NEW,
         useReadWriteLock, cacheChannels, disableAssertions);
  }

  public StorageLockContext(boolean useReadWriteLock,
                            boolean cacheChannels) {
    this(useReadWriteLock, cacheChannels, false);
  }

  public StorageLockContext(boolean useReadWriteLock) {
    this(useReadWriteLock, false, false);
  }

  public StorageLockContext() {
    this(false, false, false);
  }

  boolean useChannelCache() {
    return myCacheChannels;
  }

  public Lock readLock() {
    return myUseReadWriteLock ? myLock.readLock() : myLock.writeLock();
  }

  public Lock writeLock() {
    return myLock.writeLock();
  }

  public void lockRead() {
    if (myUseReadWriteLock) {
      myLock.readLock().lock();
    }
    else {
      myLock.writeLock().lock();
    }
  }

  public void unlockRead() {
    if (myUseReadWriteLock) {
      myLock.readLock().unlock();
    }
    else {
      myLock.writeLock().unlock();
    }
  }

  public void lockWrite() {
    myLock.writeLock().lock();
  }

  public void unlockWrite() {
    myLock.writeLock().unlock();
  }

  @NonNull
  FilePageCache getBufferCache() {
    return myFilePageCache;
  }

  /** @throws UnsupportedOperationException if new FilePageCache implementation is absent (disabled) */
  @NonNull
  public FilePageCacheLockFree pageCache() {
    if (myFilePageCacheLockFree == null) {
      if (PageCacheUtils.LOCK_FREE_VFS_ENABLED) {
        throw new UnsupportedOperationException(
          "lock-free FilePageCache is not available in this storageLockContext."
        );
      }
      throw new UnsupportedOperationException(
        "lock-free FilePageCache is not available: PageCacheUtils.ENABLE_LOCK_FREE_VFS=false."
      );
    }
    return myFilePageCacheLockFree;
  }

  public void checkWriteAccess() {
    if (!myDisableAssertions && IndexDebugProperties.DEBUG) {
        if (myLock.writeLock().isHeldByCurrentThread()) {
            return;
        }
      throw new IllegalStateException("Must hold StorageLock write lock to access PagedFileStorage");
    }
  }

  public void checkReadAccess() {
    if (!myDisableAssertions && IndexDebugProperties.DEBUG) {
        if (myLock.getReadHoldCount() > 0 || myLock.writeLock().isHeldByCurrentThread()) {
            return;
        }
      throw new IllegalStateException("Must hold StorageLock read lock to access PagedFileStorage");
    }
  }

  void assertUnderSegmentAllocationLock() {
    if (IndexDebugProperties.DEBUG) {
      myFilePageCache.assertUnderSegmentAllocationLock();
    }
  }

  public static void forceDirectMemoryCache() {
    DEFAULT_FILE_PAGE_CACHE.flushBuffers();
  }

  public static @NonNull FilePageCacheStatistics getStatistics() {
    return DEFAULT_FILE_PAGE_CACHE.getStatistics();
  }

  public static void assertNoBuffersLocked() {
    DEFAULT_FILE_PAGE_CACHE.assertNoBuffersLocked();
  }

  public static long getCacheMaxSize() {
    return DEFAULT_FILE_PAGE_CACHE.getMaxSize();
  }

  /** for monitoring purposes only */
  public static ReentrantReadWriteLock defaultContextLock(){
    return ourDefaultContext.myLock;
  }
}