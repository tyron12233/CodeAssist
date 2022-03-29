package com.tyron.builder.api.internal.snapshot;

import org.jetbrains.annotations.Nullable;

public class ValueSnapshottingException extends RuntimeException {
    public ValueSnapshottingException(String message) {
        super(message);
    }

    public ValueSnapshottingException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}