package com.tyron.builder.api.providers;

import com.tyron.builder.api.Action;

/**
 * Provides a domain object of the given type.
 *
 * @param <T> type of domain object
 * @since 4.10
 */
public interface NamedDomainObjectProvider<T> extends Provider<T> {
    /**
     * Configures the domain object with the given action. Actions are run in the order added.
     *
     * @param action A {@link Action} that can configure the domain object when required.
     * @since 4.10
     */
    void configure(Action<? super T> action);

    /**
     * The domain object name referenced by this provider.
     * <p>
     * Must be constant for the life of the object.
     *
     * @return The domain object. Never null.
     * @since 4.10
     */
    String getName();
}