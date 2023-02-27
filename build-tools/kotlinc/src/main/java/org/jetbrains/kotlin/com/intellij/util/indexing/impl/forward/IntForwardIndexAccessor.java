package org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputData;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.io.EnumeratorIntegerDescriptor;

import java.io.IOException;

public interface IntForwardIndexAccessor<Key, Value> extends ForwardIndexAccessor<Key, Value> {
  @NonNull
  @Override
  default InputDataDiffBuilder<Key, Value> getDiffBuilder(int inputId, @Nullable ByteArraySequence sequence) throws IOException {
    return getDiffBuilderFromInt(inputId, sequence == null ? 0 : AbstractForwardIndexAccessor.deserializeFromByteSeq(sequence, EnumeratorIntegerDescriptor.INSTANCE));
  }

  @Nullable
  @Override
  default ByteArraySequence serializeIndexedData(@NonNull InputData<Key, Value> data) throws IOException {
    return AbstractForwardIndexAccessor.serializeValueToByteSeq(serializeIndexedDataToInt(data), EnumeratorIntegerDescriptor.INSTANCE, 8);
  }

  /**
   * creates a diff builder for given inputId.
   */
  @NonNull
  InputDataDiffBuilder<Key, Value> getDiffBuilderFromInt(int inputId, int value) throws IOException;

  int serializeIndexedDataToInt(@NonNull InputData<Key, Value> data);
}