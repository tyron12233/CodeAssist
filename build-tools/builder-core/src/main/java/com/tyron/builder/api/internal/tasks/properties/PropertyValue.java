package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Callable;

/**
 * A supplier of a property value. The property value may not necessarily be final and may change over time.
 */
public interface PropertyValue extends Callable<Object> {
    /**
     * The value of the underlying property, replacing an empty provider by {@literal null}.
     *
     * This is required for allowing optional provider properties - all code which unpacks providers calls {@link Provider#get()} and would fail if an optional provider is passed.
     * Returning {@literal null} from a {@link Callable} is ignored, and {@link PropertyValue} is a {@link Callable}.
     */
    @Nullable
    @Override
    Object call();

    /**
     * The unprocessed value of the underlying property.
     */
    @Nullable
    Object getUnprocessedValue();

    /**
     * Returns the dependencies of the property value, if supported by the value implementation. Returns an empty collection if not supported or the value has no producer tasks.
     */
    TaskDependencyContainer getTaskDependencies();

    /**
     * Finalizes the property value, if possible. This makes the value final, so that it no longer changes, but not necessarily immutable.
     */
    void maybeFinalizeValue();
}