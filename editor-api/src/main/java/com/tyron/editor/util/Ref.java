package com.tyron.editor.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Simple value wrapper.
 *
 * @param <T> Value type.
 * @author ven
 */
public class Ref<T> {
  private T myValue;

  public Ref() { }

  public Ref(@Nullable T value) {
    myValue = value;
  }

  public final boolean isNull() {
    return myValue == null;
  }

  public final T get() {
    return myValue;
  }

  public final void set(@Nullable T value) {
    myValue = value;
  }

  public final boolean setIfNull(@Nullable T value) {
    boolean result = myValue == null && value != null;
    if (result) {
      myValue = value;
    }
    return result;
  }

  @NotNull
  public static <T> Ref<T> create() {
    return new Ref<T>();
  }

  public static <T> Ref<T> create(@Nullable T value) {
    return new Ref<T>(value);
  }

  @Nullable
  public static <T> T deref(@Nullable Ref<T> ref) {
    return ref == null ? null : ref.get();
  }

  @Override
  public String toString() {
    return String.valueOf(myValue);
  }
}