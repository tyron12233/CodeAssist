package com.tyron.builder.internal.lazy;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.function.Supplier;

@NotThreadSafe
class UnsafeLazy<T> implements Lazy<T> {
    private Supplier<T> supplier;
    private T value;

    public UnsafeLazy(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    @Override
    public T get() {
        if (supplier != null) {
            value = supplier.get();
            supplier = null;
        }
        return value;
    }
}