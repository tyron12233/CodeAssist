package com.tyron.builder.api.internal.attributes;

import com.tyron.builder.api.attributes.Attribute;
import com.tyron.builder.internal.isolation.Isolatable;

public interface ImmutableAttributesFactory {
    /**
     * Returns an empty mutable attribute container.
     */
    AttributeContainerInternal mutable();

    /**
     * Returns an empty mutable attribute container with the given parent.
     */
    AttributeContainerInternal mutable(AttributeContainerInternal parent);

    /**
     * Returns an attribute container that contains the given value.
     */
    <T> ImmutableAttributes of(Attribute<T> key, T value);

    /**
     * Adds the given attribute to the given container. Note: the container _should not_ contain the given attribute.
     */
    <T> ImmutableAttributes concat(ImmutableAttributes node, Attribute<T> key, T value);

    /**
     * Adds the given attribute to the given container. Note: the container _should not_ contain the given attribute.
     */
    <T> ImmutableAttributes concat(ImmutableAttributes node, Attribute<T> key, Isolatable<T> value);

    /**
     * Merges the second container into the first container and returns the result. Values in the second container win.
     *
     * Attributes with same name but different type are considered the same attribute for the purpose of merging. As such
     * an attribute in the second container will replace any attribute in the first container with the same name,
     * irrespective of the type of the attributes.
     */
    ImmutableAttributes concat(ImmutableAttributes attributes1, ImmutableAttributes attributes2);

    /**
     * Merges the second container into the first container and returns the result. If the second container has the same
     * attribute with a different value, this method will fail instead of overriding the attribute value.
     *
     * Attributes with same name but different type are considered equal for the purpose of merging.
     */
    ImmutableAttributes safeConcat(ImmutableAttributes attributes1, ImmutableAttributes attributes2) throws AttributeMergingException;
}
