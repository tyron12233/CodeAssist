package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class MapInputDataDiffBuilder<Key, Value> extends DirectInputDataDiffBuilder<Key, Value> {
  @NonNull
  private final Map<Key, Value> myMap;

  public MapInputDataDiffBuilder(int inputId, @Nullable Map<Key, Value> map) {
    super(inputId);
    myMap = map == null ? Collections.emptyMap() : map;
  }

  @Override
  public boolean differentiate(@NonNull Map<Key, Value> newData,
                               @NonNull KeyValueUpdateProcessor<? super Key, ? super Value> addProcessor,
                               @NonNull KeyValueUpdateProcessor<? super Key, ? super Value> updateProcessor,
                               @NonNull RemovedKeyProcessor<? super Key> removeProcessor) throws StorageException {
    if (myMap.isEmpty()) {
      return EmptyInputDataDiffBuilder.processAllKeyValuesAsAdded(myInputId, newData, addProcessor);
    }
    if (newData.isEmpty()) {
      return EmptyInputDataDiffBuilder.processAllKeyValuesAsRemoved(myInputId, myMap, removeProcessor);
    }

    int added = 0;
    int removed = 0;
    int updated = 0;

    for (Map.Entry<Key, Value> e : myMap.entrySet()) {
      Key key = e.getKey();
      Value oldValue = e.getValue();
      Value newValue = newData.get(key);
      if (!Comparing.equal(oldValue, newValue) || (newValue == null && !newData.containsKey(key))) {
        if (newData.containsKey(key)) {
          updateProcessor.process(key, newValue, myInputId);
          updated++;
        }
        else {
          removeProcessor.process(key, myInputId);
          removed++;
        }
      }
    }

    for (Map.Entry<Key, Value> e : newData.entrySet()) {
      final Key newKey = e.getKey();
      if (!myMap.containsKey(newKey)) {
        addProcessor.process(newKey, e.getValue(), myInputId);
        added++;
      }
    }

    if (IndexDebugProperties.DEBUG) {
      updateStatistics(added, removed, updated, myMap, newData);
    }

    return added != 0 || removed != 0 || updated != 0;
  }

  @NonNull
  @Override
  public Collection<Key> getKeys() {
    return myMap.keySet();
  }

  private static <Key, Value> void updateStatistics(int added,
                                                    int removed,
                                                    int updated,
                                                    @NonNull Map<Key, Value> oldData,
                                                    @NonNull Map<Key, Value> newData) {
    incrementalAdditions.addAndGet(added);
    incrementalRemovals.addAndGet(removed);
    totalRemovals.addAndGet(oldData.size());
    totalAdditions.addAndGet(newData.size());

    if (added == 0 && removed == 0 && updated == 0) {
      noopModifications.incrementAndGet();
    }

    int requests = totalRequests.incrementAndGet();
    if ((requests & 0xFFFF) == 0) {
      Logger.getInstance(MapInputDataDiffBuilder.class).info(
        "Incremental index diff update: " + requests +
        ", removals: " + totalRemovals + "->" + incrementalRemovals +
        ", additions: " + totalAdditions + "->" + incrementalAdditions +
        ", no op changes: " + noopModifications
      );
    }
  }

  private static final AtomicInteger totalRequests = new AtomicInteger();
  private static final AtomicInteger totalRemovals = new AtomicInteger();
  private static final AtomicInteger totalAdditions = new AtomicInteger();
  private static final AtomicInteger incrementalRemovals = new AtomicInteger();
  private static final AtomicInteger incrementalAdditions = new AtomicInteger();
  private static final AtomicInteger noopModifications = new AtomicInteger();
}