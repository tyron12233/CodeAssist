package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;

import java.util.Map;

/**
 * A class intended to make a diff between existing forward index data and new one.
 */
public abstract class InputDataDiffBuilder<Key, Value> {
  protected final int myInputId;

  protected InputDataDiffBuilder(int id) {myInputId = id;}
  /**
   * produce a diff between existing data and newData and consume result to addProcessor, updateProcessor and removeProcessor.
   * @return false if there is no difference and true otherwise
   */
  public abstract boolean differentiate(@NonNull Map<Key, Value> newData,
                                        @NonNull KeyValueUpdateProcessor<? super Key, ? super Value> addProcessor,
                                        @NonNull KeyValueUpdateProcessor<? super Key, ? super Value> updateProcessor,
                                        @NonNull RemovedKeyProcessor<? super Key> removeProcessor) throws StorageException;
}