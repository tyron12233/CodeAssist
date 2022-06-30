package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Action;

import java.util.Set;

/**
 * An attributes schema stores information about {@link Attribute attributes} and how they
 * can be matched together.
 *
 * @since 3.3
 *
 */
public interface AttributesSchema {

    /**
     * Returns the matching strategy for a given attribute.
     * @param attribute the attribute
     * @param <T> the type of the attribute
     * @return the matching strategy for this attribute.
     * @throws IllegalArgumentException When no strategy is available for the given attribute.
     */
    <T> AttributeMatchingStrategy<T> getMatchingStrategy(Attribute<T> attribute) throws IllegalArgumentException;

    /**
     * Declares a new attribute in the schema and configures it with the default strategy.
     * If the attribute was already declared it will simply return the existing strategy.
     *
     * @param attribute the attribute to declare in the schema
     * @param <T> the concrete type of the attribute
     *
     * @return the matching strategy for this attribute
     */
    <T> AttributeMatchingStrategy<T> attribute(Attribute<T> attribute);

    /**
     * Configures the matching strategy for an attribute. The first call to this method for a specific attribute
     * will create a new matching strategy, whereas subsequent calls will configure the existing one.
     *
     * @param attribute the attribute for which to configure the matching strategy
     * @param configureAction the strategy configuration
     * @param <T> the concrete type of the attribute
     * @return the configured strategy
     */
    <T> AttributeMatchingStrategy<T> attribute(Attribute<T> attribute, Action<? super AttributeMatchingStrategy<T>> configureAction);

    /**
     * Returns the set of attributes known to this schema.
     */
    Set<Attribute<?>> getAttributes();

    /**
     * Returns true when this schema contains the given attribute.
     */
    boolean hasAttribute(Attribute<?> key);
}
