package com.tyron.builder.internal.metaobject;

import java.util.Map;

/**
 * Provides dynamic access to properties of some object.
 */
public interface PropertyAccess {
    /**
     * Returns true when this object is known to have the given property.
     *
     * <p>Note that not every property is known. Some properties require an attempt to get or set their value before they are discovered.</p>
     */
    boolean hasProperty(String name);

    /**
     * Gets the value of the given property, if present.
     */
    DynamicInvokeResult tryGetProperty(String name);

    /**
     * Sets the value of the given property, if present.
     *
     * @return true if the property was found
     */
    DynamicInvokeResult trySetProperty(String name, Object value);

    /**
     * Returns the properties known for this object.
     */
    Map<String, ?> getProperties();

}
