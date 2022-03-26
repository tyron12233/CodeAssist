package com.tyron.builder.api.providers;

import java.util.Set;

import javax.annotation.Nullable;

/**
 * Represents a property whose type is a {@link Set} of elements of type {@link T}. Retains iteration order.
 *
 * <p>
 * You can create a {@link SetProperty} instance using factory method {@link org.gradle.api.model.ObjectFactory#setProperty(Class)}.
 * </p>
 *
 * <p><b>Note:</b> This interface is not intended for implementation by build script or plugin authors.
 *
 * @param <T> the type of elements.
 * @since 4.5
 */
public interface SetProperty<T> extends Provider<Set<T>>, HasMultipleValues<T> {
    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> empty();

    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> value(@Nullable Iterable<? extends T> elements);

    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> value(Provider<? extends Iterable<? extends T>> provider);

    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> convention(Iterable<? extends T> elements);

    /**
     * {@inheritDoc}
     */
    @Override
    SetProperty<T> convention(Provider<? extends Iterable<? extends T>> provider);
}