package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.io.IOException;

public interface PersistentMap<K, V> extends KeyValueStore<K, V> {
  /**
   * Process all keys registered in the map.
   * Note that keys which were removed at some point might be returned as well.
   */
  boolean processKeys(@NonNull Processor<? super K> processor) throws IOException;

  void remove(K key) throws IOException;

  boolean containsMapping(K key) throws IOException;

  boolean isClosed();

  boolean isDirty();

  void markDirty() throws IOException;

  /**
   * Closes the map removing all entries
   */
  default void closeAndClean() throws IOException {
    close();
  }
}