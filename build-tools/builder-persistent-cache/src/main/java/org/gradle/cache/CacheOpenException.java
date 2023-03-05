package org.gradle.cache;


import org.gradle.api.GradleException;
import org.gradle.internal.exceptions.Contextual;

@Contextual
public class CacheOpenException extends GradleException {
    public CacheOpenException(String message) {
        super(message);
    }

    public CacheOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}