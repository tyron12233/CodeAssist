package com.tyron.builder.internal.metaobject;

import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;

/**
 * An object that can be worked with in a dynamic fashion.
 *
 * The semantics of each method is completely up to the implementation. For example, {@link BeanDynamicObject}
 * provides a dynamic view of the functionality of an object and does not provide any decoration or extra functionality.
 * The {@link ExtensibleDynamicObject} implementation on the other hand does provide extra functionality.
 */
public interface DynamicObject extends MethodAccess, PropertyAccess {
    /**
     * Creates a {@link MissingPropertyException} for getting an unknown property of this object.
     */
    MissingPropertyException getMissingProperty(String name);

    /**
     * Creates a {@link MissingPropertyException} for setting an unknown property of this object.
     */
    MissingPropertyException setMissingProperty(String name);

    /**
     * Creates a {@link MissingMethodException} for invoking an unknown method on this object.
     */
    MissingMethodException methodMissingException(String name, Object... params);

    /**
     * Don't use this method. Use the overload {@link #tryGetProperty(String)} instead.
     */
    Object getProperty(String name) throws MissingPropertyException;

    /**
     * Don't use this method. Use the overload {@link #trySetProperty(String, Object)} instead.
     */
    void setProperty(String name, Object value) throws MissingPropertyException;

    /**
     * Don't use this method. Use the overload {@link MethodAccess#tryInvokeMethod(String, Object...)} instead.
     */
    Object invokeMethod(String name, Object... arguments) throws MissingMethodException;
}
