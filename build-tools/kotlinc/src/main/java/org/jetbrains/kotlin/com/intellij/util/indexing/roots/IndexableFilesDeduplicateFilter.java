package org.jetbrains.kotlin.com.intellij.util.indexing.roots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileFilter;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentBitSet;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link VirtualFileFilter} used in tandem with {@link IndexableFilesIterator} to skip files that have already been iterated.
 * Several {@link IndexableFilesIterator} might be going to iterate the same roots (for example, if two libraries reference the same .jar file).
 * <br/>
 * Note: even a single {@link IndexableFilesIterator} might potentially have interleaving roots.
 * <br/>
 * Also this class is used to {@link #getNumberOfSkippedFiles() count} files whose iteration has been skipped. This number is used in indexing diagnostics.
 * <br/>
 * This filter is intended to be used in a concurrent environment, where two {@link IndexableFilesIterator iterators} iterate files in different threads.
 */
public final class IndexableFilesDeduplicateFilter implements VirtualFileFilter {

  private final @Nullable IndexableFilesDeduplicateFilter myDelegate;
  private final ConcurrentBitSet myVisitedFileSet = ConcurrentBitSet.create();
  private final AtomicInteger myNumberOfSkippedFiles = new AtomicInteger();

  private IndexableFilesDeduplicateFilter(@Nullable IndexableFilesDeduplicateFilter delegate) {
    this.myDelegate = delegate;
  }

  /**
   * Create a new filter that counts skipped files from zero.
   */
  public static @NonNull IndexableFilesDeduplicateFilter create() {
    return new IndexableFilesDeduplicateFilter(null);
  }

  /**
   * Create a new filter that counts skipped files from zero and uses the {@code delegate} to determine whether the file should be skipped.
   * <br/>
   * Use case: if there is a "global" filter that skips iterated files across many {@link IndexableFilesIterator},
   * then this method allows to create a delegating filter that counts only files that have been skipped for a specific {@link IndexableFilesIterator}
   */
  public static @NonNull IndexableFilesDeduplicateFilter createDelegatingTo(@NonNull IndexableFilesDeduplicateFilter delegate) {
    if (delegate.myDelegate != null) {
      throw new IllegalStateException("Only one-level delegation is supported now");
    }
    return new IndexableFilesDeduplicateFilter(delegate);
  }

  public int getNumberOfSkippedFiles() {
    return myNumberOfSkippedFiles.get();
  }

  @Override
  public boolean accept(@NonNull VirtualFile file) {
    if (myDelegate != null) {
      boolean shouldVisit = myDelegate.accept(file);
      if (!shouldVisit) {
        myNumberOfSkippedFiles.incrementAndGet();
      }
      return shouldVisit;
    }

    int fileId;

    if (file instanceof VirtualFileWithId) {
      fileId = ((VirtualFileWithId)file).getId();
    } else {
      fileId = FileIdStorage.getAndStoreId(file);
    }

    if (fileId > 0) {
      boolean wasVisited = myVisitedFileSet.set(fileId);
      if (wasVisited) {
        myNumberOfSkippedFiles.incrementAndGet();
      }
      return !wasVisited;
    }

    myNumberOfSkippedFiles.incrementAndGet();
    return false;
  }
}