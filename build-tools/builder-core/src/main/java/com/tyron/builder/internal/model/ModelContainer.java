package com.tyron.builder.internal.model;

import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Encapsulates some mutable model, and provides synchronized access to the model.
 */
public interface ModelContainer<T> {

    /**
     * Runs the given function to calculate a value from the public mutable model. Applies best effort synchronization to prevent concurrent access to a particular project from multiple threads.
     * However, it is currently easy for state to leak from one project to another so this is not a strong guarantee.
     *
     * <p>It is usually a better option to use {@link #newCalculatedValue(Object)} instead of this method.</p>
     */
    <S> S fromMutableState(Function<? super T, ? extends S> factory);

    /**
     * DO NOT USE THIS METHOD. It is here to provide some specific backwards compatibility.
     */
    <S> S forceAccessToMutableState(Function<? super T, ? extends S> factory);

    /**
     * Runs the given action against the public mutable model. Applies best effort synchronization to prevent concurrent access to a particular project from multiple threads.
     * However, it is currently easy for state to leak from one project to another so this is not a strong guarantee.
     */
    void applyToMutableState(Consumer<? super T> action);

    /**
     * Returns whether or not the current thread has access to the mutable model.
     */
    boolean hasMutableState();

    /**
     * Creates a new container for a value that is calculated from the mutable state of this container, and then reused by multiple threads.
     */
    <S> CalculatedModelValue<S> newCalculatedValue(@Nullable S initialValue);
}
