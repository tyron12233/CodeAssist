package com.tyron.builder.api.internal.reflect;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;

public class CachedInvokable<T> {
    private final WeakReference<T> invokable;

    public CachedInvokable(T invokable) {
        this.invokable = new WeakReference<T>(invokable);
    }

    @Nullable
    public T getMethod() {
        return invokable.get();
    }
}
