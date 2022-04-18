package com.tyron.builder.api;

import com.tyron.builder.api.reflect.TypeOf;

/**
 * Schema of named domain object collections.
 *
 * @since 4.10
 * @see NamedDomainObjectCollection
 */
public interface NamedDomainObjectCollectionSchema {
    /**
     * Returns an iterable of the schemas for each element in the collection.
     */
    Iterable<? extends NamedDomainObjectSchema> getElements();

    /**
     * Schema of a named domain object.
     *
     * @since 4.10
     */
    interface NamedDomainObjectSchema {
        /**
         * The name of the domain object.
         */
        String getName();

        /**
         * The public type of the domain object.
         */
        TypeOf<?> getPublicType();
    }
}

