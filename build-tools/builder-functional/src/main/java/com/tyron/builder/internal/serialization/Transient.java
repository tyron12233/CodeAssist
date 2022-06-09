package com.tyron.builder.internal.serialization;

import javax.annotation.Nullable;

/**
 * A value that gets discarded during serialization.
 */
public abstract class Transient<T> implements java.io.Serializable {

    /**
     * A mutable variable that gets discarded during serialization.
     */
    public static abstract class Var<T> extends Transient<T> {
        public abstract void set(T value);
    }

    public static <T> Transient<T> of(T value) {
        return new ImmutableTransient<>(value);
    }

    public static <T> Var<T> varOf() {
        return varOf(null);
    }

    public static <T> Var<T> varOf(@Nullable T value) {
        return new MutableTransient<>(value);
    }

    @Nullable
    public abstract T get();

    private static class ImmutableTransient<T> extends Transient<T> {

        private final T value;

        public ImmutableTransient(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        private Object writeReplace() {
            return DISCARDED;
        }
    }

    private static class MutableTransient<T> extends Var<T> {

        @Nullable
        private T value;

        public MutableTransient(@Nullable T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public void set(T value) {
            this.value = value;
        }

        private Object writeReplace() {
            return DISCARDED;
        }
    }

    private static class Discarded<T> extends Var<T> {

        @Override
        public void set(T value) {
            throw new IllegalStateException("The value of this property cannot be set after it has been discarded during serialization.");
        }

        @Override
        public T get() {
            throw new IllegalStateException("The value of this property has been discarded during serialization.");
        }

        private Object readResolve() {
            return DISCARDED;
        }
    }

    private static final Transient<Object> DISCARDED = new Discarded<>();
}
