package com.tyron.builder.api.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;

/**
 * Wraps a checked exception. Carries no other context.
 */
public final class UncheckedException extends RuntimeException {
    private UncheckedException(Throwable cause) {
        super(cause);
    }

    private UncheckedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Note: always throws the failure in some form. The return value is to keep the compiler happy.
     */
    public static RuntimeException throwAsUncheckedException(Throwable t) {
        return throwAsUncheckedException(t, false);
    }

    /**
     * Note: always throws the failure in some form. The return value is to keep the compiler happy.
     */
    // TODO Use this in org.gradle.internal.Try.Failure once this gets moved out from :base-services
    public static RuntimeException throwAsUncheckedException(Throwable t, boolean preserveMessage) {
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        if (t instanceof IOException) {
            if (preserveMessage) {
                throw new UncheckedIOException(t.getMessage(), (IOException) t);
            } else {
                throw new UncheckedIOException((IOException) t);
            }
        }
        if (preserveMessage) {
            throw new UncheckedException(t.getMessage(), t);
        } else {
            throw new UncheckedException(t);
        }
    }

    public static <T> T unchecked(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw throwAsUncheckedException(e);
        }
    }

    /**
     * Unwraps passed InvocationTargetException hence making the stack of exceptions cleaner without losing information.
     *
     * Note: always throws the failure in some form. The return value is to keep the compiler happy.
     *
     * @param e to be unwrapped
     * @return an instance of RuntimeException based on the target exception of the parameter.
     */
    public static RuntimeException unwrapAndRethrow(InvocationTargetException e) {
        return UncheckedException.throwAsUncheckedException(e.getTargetException());
    }
}