package com.tyron.builder.api.internal.instantiation;

import com.tyron.builder.api.BuildException;

import org.jetbrains.annotations.Nullable;

public class ClassGenerationException extends BuildException {
    public ClassGenerationException(String message) {
        super(message);
    }

    public ClassGenerationException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
