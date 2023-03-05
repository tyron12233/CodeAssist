package org.gradle.api.attributes;

/**
 * Represents something that carries attributes by utilizing an
 * {@link AttributeContainer}
 *
 * @since 3.3
 */
public interface HasAttributes {

    /**
     * Returns the attributes
     */
    AttributeContainer getAttributes();
}
