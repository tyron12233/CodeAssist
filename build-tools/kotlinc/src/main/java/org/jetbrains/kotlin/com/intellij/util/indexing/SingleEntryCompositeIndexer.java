package org.jetbrains.kotlin.com.intellij.util.indexing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.Objects;

//@ApiStatus.OverrideOnly
public abstract class SingleEntryCompositeIndexer<V, SubIndexerType, SubIndexerVersion> extends SingleEntryIndexer<V> implements CompositeDataIndexer<Integer, V, SubIndexerType, SubIndexerVersion> {
  protected SingleEntryCompositeIndexer(boolean acceptNullValues) {
    super(acceptNullValues);
  }

  @NonNull
  @Override
  public final Map<Integer, V> map(@NonNull FileContent inputData, @NonNull SubIndexerType indexerType) {
    throw new AssertionError();
  }

  @Nullable
  @Override
  protected V computeValue(@NonNull FileContent inputData) {
    SubIndexerType subIndexerType = calculateSubIndexer(inputData);
    return subIndexerType == null ? null : computeValue(inputData, Objects.requireNonNull(subIndexerType));
  }

  @Nullable
  protected abstract V computeValue(@NonNull FileContent inputData, @NonNull SubIndexerType indexerType);
}