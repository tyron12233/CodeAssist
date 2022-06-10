package org.gradle.cache;


import org.gradle.api.BuildException;
import org.gradle.internal.exceptions.Contextual;

@Contextual
public class CacheOpenException extends BuildException {
    public CacheOpenException(String message) {
        super(message);
    }

    public CacheOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}