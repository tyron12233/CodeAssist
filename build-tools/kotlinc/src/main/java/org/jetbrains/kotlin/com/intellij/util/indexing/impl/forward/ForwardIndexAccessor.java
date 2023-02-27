package org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputData;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;

import java.io.IOException;

public interface ForwardIndexAccessor<Key, Value> {
  /**
   * creates a diff builder for given inputId.
   */
  @NonNull
  InputDataDiffBuilder<Key, Value> getDiffBuilder(int inputId, @Nullable ByteArraySequence sequence) throws IOException;

  /**
   * serialize indexed data to forward index format.
   */
  @Nullable
  ByteArraySequence serializeIndexedData(@NonNull InputData<Key, Value> data) throws IOException;
}