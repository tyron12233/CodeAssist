package org.gradle.initialization.exception;


import org.gradle.api.BuildException;
import org.gradle.internal.exceptions.Contextual;

@Contextual
public class InitializationException extends BuildException {

    public InitializationException(Throwable cause) {
        super("Gradle could not start your build.", cause);
    }
}