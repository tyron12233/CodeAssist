package org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.io.MeasurableIndexStore;

public class EmptyForwardIndex implements ForwardIndex, MeasurableIndexStore {
  @Nullable
  @Override
  public ByteArraySequence get(@NonNull Integer key) {
    return null;
  }

  @Override
  public void put(@NonNull Integer key, @Nullable ByteArraySequence value) { }

  @Override
  public void clear() { }

  @Override
  public void close() { }

  @Override
  public void force() { }

  @Override
  public int keysCountApproximately() {
    return 0;
  }
}