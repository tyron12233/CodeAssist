package com.tyron.builder.internal.serialization;

import com.tyron.builder.internal.Try;

import java.util.concurrent.Callable;

/**
 * Represents a computation that must execute only once and
 * whose result must be cached even (or specially) at serialization time.
 *
 * @param <T> the resulting type
 */
public abstract class Cached<T> {

    public static <T> Cached<T> of(Callable<T> computation) {
        return new Deferred<>(computation);
    }

    public abstract T get();

    private static class Deferred<T> extends Cached<T> implements java.io.Serializable {

        private Callable<T> computation;
        private Try<T> result;

        public Deferred(Callable<T> computation) {
            this.computation = computation;
        }

        @Override
        public T get() {
            return result().get();
        }

        private Try<T> result() {
            if (result == null) {
                result = Try.ofFailable(computation);
                computation = null;
            }
            return result;
        }

        private Object writeReplace() {
            return new Fixed<>(result());
        }
    }

    private static class Fixed<T> extends Cached<T> {

        private final Try<T> result;

        public Fixed(Try<T> result) {
            this.result = result;
        }

        @Override
        public T get() {
            return result.get();
        }
    }
}
