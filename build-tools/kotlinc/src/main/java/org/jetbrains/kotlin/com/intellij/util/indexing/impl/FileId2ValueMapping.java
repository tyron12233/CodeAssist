package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.List;

final class FileId2ValueMapping<Value> {
  private final @NonNull Int2ObjectMap<Value> id2ValueMap = new Int2ObjectOpenHashMap<>();
  private final @NonNull ValueContainerImpl<Value> valueContainer;

  FileId2ValueMapping(@NonNull ValueContainerImpl<Value> valueContainer) {
    this.valueContainer = valueContainer;

    List<Pair<Value, Integer>> cleanupDeletions = new SmartList<>();

    valueContainer.forEach((id, value) -> {
      Value previousValue = associateFileIdToValueSkippingContainer(id, value);
      if (previousValue != null) {
        cleanupDeletions.add(Pair.create(previousValue, id));
        //ValueContainerImpl.LOG.error("Duplicated value for id = " + id + " in " + valueContainer.getDebugMessage());
      }
      return true;
    });

    for (Pair<Value, Integer> deletion : cleanupDeletions) {
      valueContainer.removeValue(deletion.second, ValueContainerImpl.unwrap(deletion.first));
    }
  }

  void associateFileIdToValue(int fileId, Value value) {
    Value previousValue = associateFileIdToValueSkippingContainer(fileId, value);
    if (previousValue != null) {
      valueContainer.removeValue(fileId, ValueContainerImpl.unwrap(previousValue));
    }
    valueContainer.addValue(fileId, value);
  }

  Value associateFileIdToValueSkippingContainer(int fileId, Value value) {
    return id2ValueMap.put(fileId, ValueContainerImpl.wrapValue(value));
  }

  boolean removeFileId(int inputId) {
    Value mapped = id2ValueMap.remove(inputId);
    if (mapped != null) {
      valueContainer.removeValue(inputId, ValueContainerImpl.unwrap(mapped));
    }
    return mapped != null;
  }
}