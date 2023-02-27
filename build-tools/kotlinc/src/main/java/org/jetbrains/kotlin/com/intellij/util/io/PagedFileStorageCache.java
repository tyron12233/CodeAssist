package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.Nullable;

/**
 * Small (max 3 items) per-{@link PagedFileStorage} MRU cache of pages ({@link DirectBufferWrapper}).
 * Used to avoid relatively expensive (and guarded by global locks) access to {@link FilePageCache}
 * for the most recent pages -- which are very likely to be accessed more than once.
 */

class PagedFileStorageCache {
  private volatile CachedBuffer myLastBuffer;
  private volatile CachedBuffer myLastBuffer2;
  private volatile CachedBuffer myLastBuffer3;

  private static class CachedBuffer {
    private final DirectBufferWrapper myWrapper;
    private final long myLastPage;

    private CachedBuffer(DirectBufferWrapper wrapper, long page) {
      myWrapper = wrapper;
      myLastPage = page;
    }
  }

  void clear() {
    myLastBuffer = null;
    myLastBuffer2 = null;
    myLastBuffer3 = null;
  }

  @Nullable
  DirectBufferWrapper getPageFromCache(long page) {
    DirectBufferWrapper buffer;

    buffer = fromCache(myLastBuffer, page);
      if (buffer != null) {
          return buffer;
      }

    buffer = fromCache(myLastBuffer2, page);
      if (buffer != null) {
          return buffer;
      }

    buffer = fromCache(myLastBuffer3, page);
    return buffer;
  }

  @Nullable
  private static DirectBufferWrapper fromCache(CachedBuffer lastBuffer, long page) {
    if (lastBuffer != null && !lastBuffer.myWrapper.isReleased() &&
        lastBuffer.myLastPage == page) {
      return lastBuffer.myWrapper;
    }
    return null;
  }

  /* race */ void updateCache(long page, DirectBufferWrapper byteBufferWrapper) {
    if (myLastBuffer != null && myLastBuffer.myLastPage != page) {
      myLastBuffer3 = myLastBuffer2;
      myLastBuffer2 = myLastBuffer;
    }
    myLastBuffer = new CachedBuffer(byteBufferWrapper, page);
  }
}