package dev.ide.jvm.standin.java.lang;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * {@code java.lang.ScopedValue} for a runtime that has none: the API Java 25 made final, plus the members the
 * Java 21 preview had and 25 dropped ({@code runWhere}, {@code callWhere}, {@code getWhere},
 * {@code Carrier.get(Supplier)}), so a program written against either compiles. The VM interprets this
 * class under the name {@code java.lang.ScopedValue} (see {@code PlatformStandIns}); it lives in this package
 * only so it can be compiled and tested like any other source.
 *
 * <p>A binding is an entry in an immutable chain held per thread. One difference from the platform: the chain
 * is inherited by every thread the binding thread starts, not only by the forks of a
 * {@code StructuredTaskScope}, which is how the forks get it here.
 */
public final class ScopedValue<T> {

    /** One thread's bindings: an immutable linked chain, newest first. */
    private static final class Bindings {
        final ScopedValue<?> key;
        final Object value;
        final Bindings next;

        Bindings(ScopedValue<?> key, Object value, Bindings next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private static final InheritableThreadLocal<Bindings> CURRENT = new InheritableThreadLocal<>();

    private ScopedValue() {}

    public static <T> ScopedValue<T> newInstance() {
        return new ScopedValue<>();
    }

    public static <T> Carrier where(ScopedValue<T> key, T value) {
        return new Carrier(Objects.requireNonNull(key), value, null);
    }

    public static <T, R> R callWhere(ScopedValue<T> key, T value, Callable<? extends R> op) throws Exception {
        Objects.requireNonNull(op);
        return where(key, value).<R, Exception>call(op::call);
    }

    public static <T, R> R getWhere(ScopedValue<T> key, T value, Supplier<? extends R> op) {
        return where(key, value).get(op);
    }

    public static <T> void runWhere(ScopedValue<T> key, T value, Runnable op) {
        where(key, value).run(op);
    }

    private Bindings find() {
        for (Bindings b = CURRENT.get(); b != null; b = b.next) {
            if (b.key == this) return b;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public T get() {
        Bindings b = find();
        if (b == null) throw new NoSuchElementException();
        return (T) b.value;
    }

    public boolean isBound() {
        return find() != null;
    }

    @SuppressWarnings("unchecked")
    public T orElse(T other) {
        Bindings b = find();
        return b == null ? other : (T) b.value;
    }

    @SuppressWarnings("unchecked")
    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        Bindings b = find();
        if (b == null) throw exceptionSupplier.get();
        return (T) b.value;
    }

    /** An operation that returns a result and may throw (Java 25's argument to {@link Carrier#call}). */
    @FunctionalInterface
    public interface CallableOp<T, X extends Throwable> {
        T call() throws X;
    }

    /** A set of bindings to run an operation with. */
    public static final class Carrier {
        private final ScopedValue<?> key;
        private final Object value;
        private final Carrier previous;

        private Carrier(ScopedValue<?> key, Object value, Carrier previous) {
            this.key = key;
            this.value = value;
            this.previous = previous;
        }

        public <T> Carrier where(ScopedValue<T> key, T value) {
            return new Carrier(Objects.requireNonNull(key), value, this);
        }

        @SuppressWarnings("unchecked")
        public <T> T get(ScopedValue<T> key) {
            for (Carrier c = this; c != null; c = c.previous) {
                if (c.key == key) return (T) c.value;
            }
            throw new NoSuchElementException();
        }

        /** The thread's chain with this carrier's bindings on top, oldest binding first. */
        private Bindings bound(Bindings base) {
            Bindings under = previous == null ? base : previous.bound(base);
            return new Bindings(key, value, under);
        }

        public <R, X extends Throwable> R call(CallableOp<? extends R, X> op) throws X {
            Objects.requireNonNull(op);
            Bindings before = CURRENT.get();
            CURRENT.set(bound(before));
            try {
                return op.call();
            } finally {
                CURRENT.set(before);
            }
        }

        public <R> R get(Supplier<? extends R> op) {
            Objects.requireNonNull(op);
            Bindings before = CURRENT.get();
            CURRENT.set(bound(before));
            try {
                return op.get();
            } finally {
                CURRENT.set(before);
            }
        }

        public void run(Runnable op) {
            Objects.requireNonNull(op);
            Bindings before = CURRENT.get();
            CURRENT.set(bound(before));
            try {
                op.run();
            } finally {
                CURRENT.set(before);
            }
        }
    }
}
