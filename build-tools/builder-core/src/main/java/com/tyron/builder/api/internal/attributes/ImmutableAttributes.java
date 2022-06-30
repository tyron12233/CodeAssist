package com.tyron.builder.api.internal.attributes;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.attributes.Attribute;

public interface ImmutableAttributes extends AttributeContainerInternal {
    ImmutableAttributes EMPTY = new DefaultImmutableAttributes();

    /**
     * Locates the entry for the given attribute. Returns a 'missing' value when not present.
     */
    <T> AttributeValue<T> findEntry(Attribute<T> key);

    /**
     * Locates the entry for the attribute with the given name. Returns a 'missing' value when not present.
     */
    AttributeValue<?> findEntry(String key);

    @Override
    ImmutableSet<Attribute<?>> keySet();
}
