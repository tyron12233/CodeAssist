package org.jetbrains.kotlin.com.intellij.util.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.containers.BiDirectionalEnumerator;

public final class InMemoryDataEnumerator<Data> implements DataEnumeratorEx<Data> {
  private final BiDirectionalEnumerator<Data> myEnumerator = new BiDirectionalEnumerator<>(16);

  @Override
  public int tryEnumerate(Data name) {
    return myEnumerator.contains(name) ? myEnumerator.enumerate(name) : 0;
  }

  @Override
  public int enumerate(@Nullable Data value) {
    return myEnumerator.enumerate(value);
  }

  @Override
  public @NonNull Data valueOf(int idx) {
    return myEnumerator.getValue(idx);
  }
}