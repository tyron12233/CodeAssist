package org.jetbrains.kotlin.com.intellij.util.indexing;

public enum FileIndexingState {
  NOT_INDEXED,
  OUT_DATED,
  UP_TO_DATE;

  public boolean updateRequired() {
    return this != UP_TO_DATE;
  }
}