package com.tyron.builder.api.internal.typeconversion;

/**
 * Thrown when a given value cannot be converted to the target type.
 */
public class TypeConversionException extends RuntimeException {
    public TypeConversionException(String message) {
        super(message);
    }

    public TypeConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}