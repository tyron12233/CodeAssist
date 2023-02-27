package org.jetbrains.kotlin.com.intellij.openapi.util;

import java.lang.ref.SoftReference;

public final class ThreadLocalCachedIntArray {
  private final ThreadLocal<SoftReference<int[]>> myThreadLocal = new ThreadLocal<>();

  public int[] getBuffer(int size) {
    int[] value = org.jetbrains.kotlin.com.intellij.reference.SoftReference.dereference(myThreadLocal.get());
    if (value == null || value.length <= size) {
      value = new int[size];
      myThreadLocal.set(new SoftReference<>(value));
    }

    return value;
  }
}