package com.tyron.builder.api.reflect;

/**
 * Thrown when an object cannot be instantiated.
 *
 * @since 4.2
 */
public class ObjectInstantiationException extends RuntimeException {
    public ObjectInstantiationException(Class<?> targetType, Throwable throwable) {
        super(String.format("Could not create an instance of type %s.", targetType.getName()), throwable);
    }
}
