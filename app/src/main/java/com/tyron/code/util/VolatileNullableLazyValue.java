package com.tyron.code.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.util.Factory;
import org.jetbrains.kotlin.com.intellij.openapi.util.NullableLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.util.RecursionGuard;
import org.jetbrains.kotlin.com.intellij.openapi.util.RecursionManager;

public abstract class VolatileNullableLazyValue<T> extends NullableLazyValue<T> {

    private volatile boolean myComputed;
    private volatile @Nullable T myValue;

    @SuppressWarnings("DeprecatedIsStillUsed")
    public VolatileNullableLazyValue() { }

    @Override
    @SuppressWarnings("DuplicatedCode")
    public @Nullable T getValue() {
        boolean computed = myComputed;
        T value = myValue;
        if (!computed) {
            RecursionGuard.StackStamp stamp = RecursionManager.markStack();
            value = compute();
            if (stamp.mayCacheNow()) {
                myValue = value;
                myComputed = true;
            }
        }
        return value;
    }

    public boolean isComputed() {
        return myComputed;
    }

    public static @NonNull <T> VolatileNullableLazyValue<T> createValue(@NonNull Factory<? extends T> value) {
        return new VolatileNullableLazyValue<T>() {
            @Override
            protected @Nullable T compute() {
                return value.create();
            }
        };
    }
}
