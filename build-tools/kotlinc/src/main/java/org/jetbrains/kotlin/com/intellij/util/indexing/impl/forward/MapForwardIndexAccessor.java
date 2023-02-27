package org.jetbrains.kotlin.com.intellij.util.indexing.impl.forward;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InputData;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;

import java.util.Map;

public class MapForwardIndexAccessor<Key, Value> extends AbstractMapForwardIndexAccessor<Key, Value, Map<Key, Value>> {
  public MapForwardIndexAccessor(@NotNull DataExternalizer<Map<Key, Value>> externalizer) {
    super(externalizer);
  }

  @Nullable
  @Override
  protected Map<Key, Value> convertToMap(int inputId, @Nullable Map<Key, Value> inputData) {
    return inputData;
  }

  @Override
  protected int getBufferInitialSize(@NotNull Map<Key, Value> map) {
    return 4 * map.size();
  }
  @Nullable
  @Override
  public Map<Key, Value> convertToDataType(@NotNull InputData<Key, Value> data) {
    Map<Key, Value> map = data.getKeyValues();
    return map.isEmpty() ? null : map;
  }
}