package com.tyron.builder.api.providers;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Represents a property whose type is a {@link List} of elements of type {@link T}.
 *
 * <p>
 * You can create a {@link ListProperty} instance using factory method {@link org.gradle.api.model.ObjectFactory#listProperty(Class)}.
 * </p>
 *
 * <p><b>Note:</b> This interface is not intended for implementation by build script or plugin authors.
 *
 * @param <T> the type of elements.
 * @since 4.3
 */
public interface ListProperty<T> extends Provider<List<T>>, HasMultipleValues<T> {
    /**
     * {@inheritDoc}
     */
    @Override
    ListProperty<T> empty();

    /**
     * {@inheritDoc}
     */
    @Override
    ListProperty<T> value(@Nullable Iterable<? extends T> elements);

    /**
     * {@inheritDoc}
     */
    @Override
    ListProperty<T> value(Provider<? extends Iterable<? extends T>> provider);

    /**
     * {@inheritDoc}
     */
    @Override
    ListProperty<T> convention(Iterable<? extends T> elements);

    /**
     * {@inheritDoc}
     */
    @Override
    ListProperty<T> convention(Provider<? extends Iterable<? extends T>> provider);
}