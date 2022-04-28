package com.tyron.builder.internal.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ClassDetails {
    /**
     * Returns the non-private properties of this class. Includes inherited properties
     */
    Set<String> getPropertyNames();

    /**
     * Returns the non-private properties of this class. Includes inherited properties
     */
    Collection<? extends PropertyDetails> getProperties();

    /**
     * Returns the details of a non-private property of this class.
     */
    PropertyDetails getProperty(String name) throws NoSuchPropertyException;

    /**
     * Returns all methods of this class, including all inherited, private and static methods.
     */
    List<Method> getAllMethods();

    /**
     * Returns the non-private instance methods of this class that are not property getter or setter methods.
     * Includes inherited methods.
     */
    Collection<Method> getInstanceMethods();

    /**
     * Returns all instance fields of this class. Includes inherited fields.
     */
    List<Field> getInstanceFields();
}