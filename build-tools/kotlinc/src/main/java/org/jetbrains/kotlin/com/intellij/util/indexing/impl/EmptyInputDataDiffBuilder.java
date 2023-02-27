package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public final class EmptyInputDataDiffBuilder<Key, Value> extends DirectInputDataDiffBuilder<Key, Value> {
  public EmptyInputDataDiffBuilder(int inputId) {
    super(inputId);
  }

  @Override
  public @NonNull Collection<Key> getKeys() {
    return Collections.emptySet();
  }

  @Override
  public boolean differentiate(@NonNull Map<Key, Value> newData,
                               @NonNull final KeyValueUpdateProcessor<? super Key, ? super Value> addProcessor,
                               @NonNull KeyValueUpdateProcessor<? super Key, ? super Value> updateProcessor,
                               @NonNull RemovedKeyProcessor<? super Key> removeProcessor) throws StorageException {
    return processAllKeyValuesAsAdded(myInputId, newData, addProcessor);
  }

  public static <Key, Value> boolean processAllKeyValuesAsAdded(int inputId,
                                                                @NonNull Map<Key, Value> addedData,
                                                                @NonNull final KeyValueUpdateProcessor<? super Key, ? super Value> addProcessor) throws StorageException {
    boolean anyAdded = false;
    for (Map.Entry<Key, Value> entry : addedData.entrySet()) {
      addProcessor.process(entry.getKey(), entry.getValue(), inputId);
      anyAdded = true;
    }

    return anyAdded;
  }

  public static <Key, Value> boolean processAllKeyValuesAsRemoved(int inputId,
                                                                  @NonNull Map<Key, Value> removedData,
                                                                  @NonNull RemovedKeyProcessor<? super Key> removedProcessor) throws StorageException {
    boolean anyRemoved = false;
    for (Key key : removedData.keySet()) {
      removedProcessor.process(key, inputId);
      anyRemoved = true;
    }
    return anyRemoved;
  }
}