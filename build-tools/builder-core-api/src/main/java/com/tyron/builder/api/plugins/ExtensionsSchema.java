package com.tyron.builder.api.plugins;

import com.tyron.builder.api.NamedDomainObjectCollectionSchema;
import com.tyron.builder.api.reflect.TypeOf;

/**
 * Schema of extensions.
 *
 * @see ExtensionContainer
 * @since 4.5
 */
public interface ExtensionsSchema extends NamedDomainObjectCollectionSchema, Iterable<ExtensionsSchema.ExtensionSchema> {

    /**
     * {@inheritDoc}
     */
    @Override
    Iterable<ExtensionSchema> getElements();

    /**
     * Schema of an extension.
     *
     * @since 4.5
     */
    interface ExtensionSchema extends NamedDomainObjectCollectionSchema.NamedDomainObjectSchema {

        /**
         * The name of the extension.
         *
         * @return the name of the extension
         */
        @Override
        String getName();

        /**
         * The public type of the extension.
         *
         * @return the public type of the extension
         */
        @Override
        TypeOf<?> getPublicType();
    }
}

