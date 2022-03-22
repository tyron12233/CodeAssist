package com.tyron.builder.api.internal;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * A non-thread-safe type to hold a reference to a single value.
 */
public final class MutableReference<T> implements Serializable {
    private T value;

    public static <T> MutableReference<T> empty() {
        return of(null);
    }

    public static <T> MutableReference<T> of(@Nullable T initialValue) {
        return new MutableReference<T>(initialValue);
    }

    private MutableReference(@Nullable T initialValue) {
        this.value = initialValue;
    }

    public void set(@Nullable T value) {
        this.value = value;
    }

    @Nullable
    public T get() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}