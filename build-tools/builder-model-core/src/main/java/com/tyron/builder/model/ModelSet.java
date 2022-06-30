package com.tyron.builder.model;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Incubating;

import java.util.Set;

/**
 * A set of managed model objects.
 * <p>
 * {@link com.tyron.builder.model.Managed} types may declare managed set properties.
 * Managed sets can only contain managed types.
 * <p>
 * Managed set objects cannot be mutated via the mutative methods of the {@link java.util.Set} interface (e.g. {@link java.util.Set#add(Object)}, {@link java.util.Set#clear()}).
 * To add elements to the set, the {@link #create(Action)} method can be used.
 *
 * @param <T> the type of model object
 */
@Incubating
public interface ModelSet<T> extends Set<T>, ModelElement {

    /**
     * Declares a new set element, configured by the given action.
     *
     * @param action the object configuration
     */
    void create(Action<? super T> action);

    /**
     * Apply the given action to each set element just after it is created.
     * <p>
     * The configuration action is equivalent in terms of lifecycle to {@link com.tyron.builder.model.Defaults} rule methods.
     *
     * @param configAction the object configuration
     */
    void beforeEach(Action<? super T> configAction);

    /**
     * Apply the given action to each set element just before it is considered to be realised.
     * <p>
     * The configuration action is equivalent in terms of lifecycle to {@link com.tyron.builder.model.Finalize} rule methods.
     *
     * @param configAction the object configuration
     */
    void afterEach(Action<? super T> configAction);
}
