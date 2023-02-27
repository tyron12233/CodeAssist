package org.jetbrains.kotlin.com.intellij.util.indexing.snapshot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputData;

import java.util.Map;

public class HashedInputData<Key, Value> extends InputData<Key, Value> {
  private final int myHashId;

  protected HashedInputData(@NotNull Map<Key, Value> values, int hashId) {
    super(values);
    myHashId = hashId;
  }

  public int getHashId() {
    return myHashId;
  }
}