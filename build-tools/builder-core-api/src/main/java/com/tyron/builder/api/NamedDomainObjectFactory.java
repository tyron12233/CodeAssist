package com.tyron.builder.api;

/**
 * A factory for named objects of type {@code T}.
 *
 * @param <T> The type of objects which this factory creates.
 */
public interface NamedDomainObjectFactory<T> {
    /**
     * Creates a new object with the given name.
     *
     * @param name The name
     * @return The object.
     */
    T create(String name);
}
