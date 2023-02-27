package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;

import java.io.IOException;

public abstract class AbstractUpdateData<Key, Value> {
  private final int myInputId;

  protected AbstractUpdateData(int id) {myInputId = id;}

  protected abstract boolean iterateKeys(@NonNull KeyValueUpdateProcessor<? super Key, ? super Value> addProcessor,
                                         @NonNull KeyValueUpdateProcessor<? super Key, ? super Value> updateProcessor,
                                         @NonNull RemovedKeyProcessor<? super Key> removeProcessor) throws StorageException;

  public int getInputId() {
    return myInputId;
  }

  protected void updateForwardIndex() throws IOException { }

}