package org.gradle.internal.reflect;

import org.gradle.internal.exceptions.Contextual;

/**
 * Thrown when a property is set to an unsupported value.
 */
@Contextual
public class UnsupportedPropertyValueException extends RuntimeException {
    public UnsupportedPropertyValueException(String message, Throwable cause) {
        super(message, cause);
    }
}
