package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputData;

/**
 * Base interface for the <a href="https://en.wikipedia.org/wiki/Search_engine_indexing#Inverted_indices">inverted indexes</a>.
 */
public interface InvertedIndex<Key, Value, Input> {
  @NonNull
  ValueContainer<Value> getData(@NonNull Key key) throws StorageException;

  /**
   * Maps input as the first stage and returns a computation that does actual index data structure update.
   * It may be used to separate long-running input mapping from writing data to disk.
   * Computable returns `true` if data has been saved without errors, otherwise - `false`.
   */
  @NonNull
  Computable<Boolean> mapInputAndPrepareUpdate(int inputId, @Nullable Input content);

  @NonNull Computable<Boolean> prepareUpdate(int inputId, @NonNull InputData<Key, Value> data);

  void flush() throws StorageException;

  void clear() throws StorageException;

  void dispose();
}