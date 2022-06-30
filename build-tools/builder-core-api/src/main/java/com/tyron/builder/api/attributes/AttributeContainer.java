package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.internal.HasInternalProtocol;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * An attribute container is a container of {@link Attribute attributes}, which are
 * strongly typed named entities. Such a container is responsible for storing and
 * getting attributes in a type safe way. In particular, attributes are strongly typed,
 * meaning that when we get a value from the container, the returned value type is
 * inferred from the type of the attribute. In a way, an attribute container is
 * similar to a {@link java.util.Map} where the entry is a "typed String" and the value
 * is of the string type. However the set of methods available to the container is
 * much more limited.
 *
 * It is not allowed to have two attributes with the same name but different types in
 * the container.
 *
 * @since 3.3
 */
@HasInternalProtocol
//@UsedByScanPlugin
public interface AttributeContainer extends HasAttributes {

    /**
     * Returns the set of attribute keys of this container.
     * @return the set of attribute keys.
     */
    Set<Attribute<?>> keySet();

    /**
     * Sets an attribute value. It is not allowed to use <code>null</code> as
     * an attribute value.
     * @param <T> the type of the attribute
     * @param key the attribute key
     * @param value the attribute value
     * @return this container
     */
    <T> AttributeContainer attribute(Attribute<T> key, T value);

    /**
     * Sets an attribute to have the same value as the given provider.
     * This attribute will track the value of the provider and query its value when this container is finalized.
     * <p>
     * This method can NOT be used to discard the value of an property. Specifying a {@code null} provider will result
     * in an {@code IllegalArgumentException} being thrown. When the provider has no value at finalization time,
     * an {@code IllegalStateException} - regardless of whether or not a convention has been set.
     * </p>
     *
     * @param <T> the type of the attribute
     * @param key the attribute key
     * @param provider The provider whose value to use
     * @return this container
     * @since 7.4
     */
    @Incubating
    <T> AttributeContainer attributeProvider(Attribute<T> key, Provider<? extends T> provider);

    /**
     * Returns the value of an attribute found in this container, or <code>null</code> if
     * this container doesn't have it.
     * @param <T> the type of the attribute
     * @param key the attribute key
     * @return the attribute value, or null if not found
     */
    @Nullable
    <T> T getAttribute(Attribute<T> key);

    /**
     * Returns true if this container is empty.
     * @return true if this container is empty.
     */
    boolean isEmpty();

    /**
     * Tells if a specific attribute is found in this container.
     * @param key the key of the attribute
     * @return true if this attribute is found in this container.
     */
    boolean contains(Attribute<?> key);

}
