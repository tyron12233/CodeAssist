package org.jetbrains.kotlin.com.intellij.util.io.pagecache.impl;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * For test implementations which usually need to override only 1-2 methods
 */
public abstract class PageStorageHandleHelper implements PageToStorageHandle {
  @Override
  public void pageBecomeDirty() {
  }

  @Override
  public void pageBecomeClean() {
  }

  @Override
  public void modifiedRegionUpdated(final long startOffsetInFile,
                                    final int length) {
  }

  @Override
  public void flushBytes(final @NonNull ByteBuffer dataToFlush,
                         final long offsetInFile) throws IOException {
  }
}