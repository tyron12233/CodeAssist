package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.Map;

public class InputData<Key, Value> {
  @SuppressWarnings("rawtypes")
  private static final InputData EMPTY = new InputData<>(Collections.emptyMap());

  @SuppressWarnings("unchecked")
  public static <Key, Value> InputData<Key, Value> empty() {
    return EMPTY;
  }

  @NonNull
  private final Map<Key, Value> myKeyValues;

  protected InputData(@NonNull Map<Key, Value> values) {
    myKeyValues = values;
  }

  @NonNull
  public Map<Key, Value> getKeyValues() {
    return myKeyValues;
  }
}