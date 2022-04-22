package com.tyron.builder.internal.lazy;


import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A wrapper around a value computed lazily. Multiple implementations
 * are possible and creating a lazy provider can be done by calling
 * one of the factory methods:
 * <ul>
 *     <li>{@link #unsafe()} would create a lazy wrapper which performs no synchronization at all when calling the supplier: it may be called several times concurrently by different threads. Not thread safe!</li>
 *     <li>{@link #locking()} would create a lazy wrapper which performs locking when calling the supplier: the supplier will only be called once. Reading is done without locking once initialized.</li>
 * </ul>
 *
 * @param <T> the type of the lazy value
 */
public interface Lazy<T> extends Supplier<T> {
    /**
     * Executes an operation on the lazily computed value
     *
     * @param consumer the consumer
     */
    default void use(Consumer<? super T> consumer) {
        consumer.accept(get());
    }

    /**
     * Applies a function to the lazily computed value and returns its value
     *
     * @param function the value to apply to the lazily computed value
     * @param <V> the return type
     * @return the result of the function, applied on the lazily computed value
     */
    default <V> V apply(Function<? super T, V> function) {
        return function.apply(get());
    }

    /**
     * Creates another lazy wrapper which will eventually apply the supplied
     * function to the lazily computed value
     *
     * @param mapper the mapping function
     * @param <V> the type of the result of the function
     * @return a new lazy wrapper
     */
    default <V> Lazy<V> map(Function<? super T, V> mapper) {
        return unsafe().of(() -> mapper.apply(get()));
    }

    static Factory unsafe() {
        return UnsafeLazy::new;
    }

    static Factory locking() {
        return LockingLazy::new;
    }

    interface Factory {
        <T> Lazy<T> of(Supplier<T> supplier);
    }

}