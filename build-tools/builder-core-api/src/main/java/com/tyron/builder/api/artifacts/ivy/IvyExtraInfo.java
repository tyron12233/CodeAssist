package com.tyron.builder.api.artifacts.ivy;

import com.tyron.builder.api.InvalidUserDataException;

import javax.annotation.Nullable;
import javax.xml.namespace.QName;
import java.util.Map;

/**
 * Represents the set of "extra" info elements in the Ivy descriptor.  These elements
 * are children of the "ivy" element, but are not defined in the Ivy schema and come
 * from other namespaces.
 */
public interface IvyExtraInfo {
    /**
     * Returns the value of the element with the unique element name.  If there are multiple elements with the same element name,
     * in different namespaces, a {@link com.tyron.builder.api.InvalidUserDataException} will be thrown.
     *
     * @param name The unique name of the element whose value should be returned
     * @return The value of the element, or null if there is no such element.
     */
    @Nullable
    String get(String name) throws InvalidUserDataException;

    /**
     * Returns the value of the element with the name and namespace provided.
     *
     * @param namespace The namespace of the element whose value should be returned
     * @param name The name of the element whose value should be returned
     * @return The value of the element, or null if there is no such element.
     */
    @Nullable
    String get(String namespace, String name);

    /**
     * Returns a map view of the 'extra' info elements such that each key is a javax.xml.namespace.QName
     * representing the namespace and name of the element and each value is the content of the element.
     *
     * @return The map view of the extra info elements. Returns an empty map if there are no elements.
     */
    Map<QName, String> asMap();
}
