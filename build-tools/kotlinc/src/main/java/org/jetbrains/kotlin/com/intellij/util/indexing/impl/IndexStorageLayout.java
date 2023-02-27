package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward.ForwardIndexAccessor;

import java.io.IOException;

/**
 * A main interface to provide custom inverted index implementation.
 */
//@ApiStatus.Experimental
//@ApiStatus.Internal
public interface IndexStorageLayout<Key, Value> {
  @NonNull
  IndexStorage<Key, Value> openIndexStorage() throws IOException;

  default @Nullable ForwardIndex  openForwardIndex() throws IOException {
    return null;
  }

  default @Nullable ForwardIndexAccessor<Key, Value> getForwardIndexAccessor() throws IOException {
    return null;
  }
}