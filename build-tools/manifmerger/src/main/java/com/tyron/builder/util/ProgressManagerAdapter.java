package com.tyron.builder.util;

import org.jetbrains.annotations.NotNull;

import com.google.common.base.Preconditions;
import java.util.concurrent.CancellationException;

/**
 * An adapter for accessing environment-dependent progress and cancellation functionality. By
 * default all public methods of this class have no effect. See subclasses for descriptions of
 * behavior in specific environments.
 */
public abstract class ProgressManagerAdapter {
    private static ProgressManagerAdapter ourInstance;

    /**
     * Checks if the progress indicator associated with the current thread has been canceled and, if
     * so, throws an unchecked exception. The exact type of the exception is environment-dependent.
     */
    public static void checkCanceled() {
        ProgressManagerAdapter instance = ourInstance;
        if (instance != null) {
            instance.doCheckCanceled();
        }
    }

    /**
     * Rethrows the given exception if it means that the current computation was cancelled. This
     * method is intended to be used in the following context:
     *
     * <pre>
     *     try {
     *         // Code that calls ProgressManagerAdapter.checkCancelled()
     *     }
     *     catch (Exception e) {
     *         ProgressManagerAdapter.throwIfCancellation(e);
     *         // Handle other exceptions.
     *     }
     * </pre>
     */
    public static void throwIfCancellation(@NotNull Throwable t) {
        ProgressManagerAdapter instance = ourInstance;
        if (instance == null) {
            throwIfCancellationException(t);
        } else {
            instance.doThrowIfCancellation(t);
        }
    }

    protected abstract void doCheckCanceled();

    protected void doThrowIfCancellation(@NotNull Throwable t) {
        throwIfCancellationException(t);
    }

    private static void throwIfCancellationException(@NotNull Throwable t) {
        if (t instanceof CancellationException) {
            throw (CancellationException) t;
        }
    }

    protected static void setInstance(@NotNull ProgressManagerAdapter instance) {
        Preconditions.checkState(ourInstance == null);
        ourInstance = instance;
    }
}

