package org.jetbrains.kotlin.com.intellij.util.indexing.impl;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;

@ApiStatus.Internal
@VisibleForTesting
public final class ValueContainerExternalizer<T> implements DataExternalizer<UpdatableValueContainer<T>> {
  private final @NotNull DataExternalizer<T> myValueExternalizer;
  private final @NotNull ValueContainerInputRemapping myInputRemapping;

  public ValueContainerExternalizer(@NotNull DataExternalizer<T> valueExternalizer, @NotNull ValueContainerInputRemapping inputRemapping) {
    myValueExternalizer = valueExternalizer;
    myInputRemapping = inputRemapping;
  }

  @Override
  public void save(@NotNull final DataOutput out, @NotNull final UpdatableValueContainer<T> container) throws IOException {
    container.saveTo(out, myValueExternalizer);
  }

  @NotNull
  @Override
  public UpdatableValueContainer<T> read(@NotNull final DataInput in) throws IOException {
    final ValueContainerImpl<T> valueContainer = new ValueContainerImpl<>();
    valueContainer.readFrom((DataInputStream)in, myValueExternalizer, myInputRemapping);
    return valueContainer;
  }
}