package org.gradle.internal.instantiation;

import org.gradle.api.GradleException;

import org.jetbrains.annotations.Nullable;

public class ClassGenerationException extends GradleException {
    public ClassGenerationException(String message) {
        super(message);
    }

    public ClassGenerationException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
