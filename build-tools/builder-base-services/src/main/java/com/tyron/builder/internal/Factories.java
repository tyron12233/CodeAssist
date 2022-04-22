package com.tyron.builder.internal;

import java.lang.ref.SoftReference;
import java.util.concurrent.atomic.AtomicReference;

public final class Factories {

    private Factories() {
        /* no-op */
    }

    public static <T> Factory<T> toFactory(final Runnable runnable) {
        return new Factory<T>() {
            @Override
            public T create() {
                runnable.run();
                return null;
            }
        };
    }

    public static <T> Factory<T> constant(final T item) {
        return new Factory<T>() {
            @Override
            public T create() {
                return item;
            }
        };
    }

    public static <T> Factory<T> softReferenceCache(Factory<T> factory) {
        return new CachingSoftReferenceFactory<T>(factory);
    }

    private static class CachingSoftReferenceFactory<T> implements Factory<T> {
        private final Factory<T> factory;
        private final AtomicReference<SoftReference<T>> cachedReference = new AtomicReference<SoftReference<T>>();

        public CachingSoftReferenceFactory(Factory<T> factory) {
            this.factory = factory;
        }

        @Override
        public T create() {
            SoftReference<T> reference = cachedReference.get();
            T value = reference != null ? reference.get() : null;
            if (value == null) {
                value = factory.create();
                cachedReference.set(new SoftReference<T>(value));
            }
            return value;
        }
    }
}