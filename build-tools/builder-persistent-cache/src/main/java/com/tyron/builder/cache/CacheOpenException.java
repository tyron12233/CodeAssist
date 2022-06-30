package com.tyron.builder.cache;


import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.exceptions.Contextual;

@Contextual
public class CacheOpenException extends BuildException {
    public CacheOpenException(String message) {
        super(message);
    }

    public CacheOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}