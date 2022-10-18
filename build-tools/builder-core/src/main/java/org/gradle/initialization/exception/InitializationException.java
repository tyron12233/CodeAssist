package org.gradle.initialization.exception;


import org.gradle.api.GradleException;
import org.gradle.internal.exceptions.Contextual;

@Contextual
public class InitializationException extends GradleException {

    public InitializationException(Throwable cause) {
        super("Gradle could not start your build.", cause);
    }
}