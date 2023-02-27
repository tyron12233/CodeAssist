package org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.io.EnumeratorIntegerDescriptor;

import java.io.IOException;

public interface IntForwardIndex extends ForwardIndex {

  int getInt(int key) throws IOException;

  void putInt(int  key, int value) throws IOException;

  @Nullable
  @Override
  default ByteArraySequence get(@NonNull Integer key) throws IOException {
    int intValue = getInt(key);
    return AbstractForwardIndexAccessor.serializeValueToByteSeq(intValue, EnumeratorIntegerDescriptor.INSTANCE, 4);
  }

  @Override
  default void put(@NonNull Integer key, @Nullable ByteArraySequence value) throws IOException {
    int valueAsInt = value == null ? 0 : AbstractForwardIndexAccessor.deserializeFromByteSeq(value, EnumeratorIntegerDescriptor.INSTANCE);
    putInt(key, valueAsInt);
  }
}