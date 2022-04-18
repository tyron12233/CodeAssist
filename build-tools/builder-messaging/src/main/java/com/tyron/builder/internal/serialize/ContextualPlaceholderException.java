package com.tyron.builder.internal.serialize;

import org.jetbrains.annotations.Nullable;

public class ContextualPlaceholderException extends PlaceholderException {
    public ContextualPlaceholderException(String exceptionClassName, @Nullable String message, @Nullable Throwable getMessageException, @Nullable String toString, @Nullable Throwable toStringException, @Nullable Throwable cause) {
        super(exceptionClassName, message, getMessageException, toString, toStringException, cause);
    }
}