package org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.ByteArraySequence;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.MapInputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;

import java.io.IOException;
import java.util.Map;

public abstract class AbstractMapForwardIndexAccessor<Key, Value, DataType> extends AbstractForwardIndexAccessor<Key, Value, DataType> {
  public AbstractMapForwardIndexAccessor(@NonNull DataExternalizer<DataType> externalizer) {
    super(externalizer);
  }

  @Override
  protected final InputDataDiffBuilder<Key, Value> createDiffBuilder(int inputId, @Nullable DataType inputData) throws IOException {
    Map<Key, Value> map = convertToMap(inputId, inputData);
    return createDiffBuilderByMap(inputId, map);
  }

  @NonNull
  public InputDataDiffBuilder<Key, Value> createDiffBuilderByMap(int inputId, @Nullable Map<Key, Value> map) throws IOException {
    return new MapInputDataDiffBuilder<>(inputId, map);
  }

  @Nullable
  protected abstract Map<Key, Value> convertToMap(int inputId, @Nullable DataType inputData) throws IOException;

  @Nullable
  public Map<Key, Value> convertToInputDataMap(int inputId, @Nullable ByteArraySequence sequence) throws IOException {
    return convertToMap(inputId, deserializeData(sequence));
  }
}