package com.tyron.builder.initialization.exception;


import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.exceptions.Contextual;

@Contextual
public class InitializationException extends BuildException {

    public InitializationException(Throwable cause) {
        super("Gradle could not start your build.", cause);
    }
}