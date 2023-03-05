package org.gradle.api.internal.lambdas;

import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.specs.Spec;
import org.gradle.internal.Factory;

import java.io.Serializable;
import java.util.function.Predicate;

/**
 * Provides a mechanism for creating Java lambdas that can be stored to the configuration cache.
 *
 * The methods on this class are meant to be statically imported.
 */
public class SerializableLambdas {

    public static <T> Predicate<T> predicate(SerializableSpec<T> spec) {
        return spec;
    }

    public static <T> Spec<T> spec(SerializableSpec<T> spec) {
        return spec;
    }

    public static <T> Action<T> action(SerializableAction<T> action) {
        return action;
    }

    public static <T> Factory<T> factory(SerializableFactory<T> factory) {
        return factory;
    }

    public static <OUT, IN> Transformer<OUT, IN> transformer(SerializableTransformer<OUT, IN> transformer) {
        return transformer;
    }

    /**
     * A {@link Serializable} version of {@link Spec}.
     */
    public interface SerializableSpec<T> extends Spec<T>, Serializable {
    }

    /**
     * A {@link Serializable} version of {@link Action}.
     */
    public interface SerializableAction<T> extends Action<T>, Serializable {
    }

    /**
     * A {@link Serializable} version of {@link Factory}.
     */
    public interface SerializableFactory<T> extends Factory<T>, Serializable {
    }

    /**
     * A {@link Serializable} version of {@link Transformer}.
     */
    public interface SerializableTransformer<OUT, IN> extends Transformer<OUT, IN>, Serializable {
    }

    private SerializableLambdas() {
    }
}