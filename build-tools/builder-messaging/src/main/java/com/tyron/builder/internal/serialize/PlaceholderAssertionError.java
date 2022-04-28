package com.tyron.builder.internal.serialize;

import com.tyron.builder.internal.UncheckedException;

import org.jetbrains.annotations.Nullable;

/**
 * A {@code PlaceholderException} is used when an assertion error cannot be serialized or deserialized.
 */
public class PlaceholderAssertionError extends AssertionError implements PlaceholderExceptionSupport {
    private final String exceptionClassName;
    private final Throwable getMessageException;
    private final String toString;
    private final Throwable toStringRuntimeEx;

    public PlaceholderAssertionError(String exceptionClassName,
                                     @Nullable String message,
                                     @Nullable Throwable getMessageException,
                                     @Nullable String toString,
                                     @Nullable Throwable toStringException,
                                     @Nullable Throwable cause) {
        super(message);
        this.exceptionClassName = exceptionClassName;
        this.getMessageException = getMessageException;
        this.toString = toString;
        this.toStringRuntimeEx = toStringException;
        initCause(cause);
    }

    @Override
    public String getExceptionClassName() {
        return exceptionClassName;
    }

    @Override
    public String getMessage() {
        if (getMessageException != null) {
            throw UncheckedException.throwAsUncheckedException(getMessageException);
        }
        return super.getMessage();
    }

    public String toString() {
        if (toStringRuntimeEx != null) {
            throw UncheckedException.throwAsUncheckedException(toStringRuntimeEx);
        }
        return toString;
    }
}