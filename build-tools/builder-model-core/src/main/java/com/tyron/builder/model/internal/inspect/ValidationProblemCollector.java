package com.tyron.builder.model.internal.inspect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public interface ValidationProblemCollector {
    boolean hasProblems();

    /**
     * Adds a problem with the type.
     */
    void add(String problem);

    /**
     * Adds a problem with a field.
     */
    void add(Field field, String problem);

    /**
     * Adds a problem with a method.
     */
    void add(Method method, String role, String problem);

    /**
     * Adds a problem with a constructor.
     */
    void add(Constructor<?> constructor, String problem);
}
