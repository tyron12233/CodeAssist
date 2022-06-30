package com.tyron.builder.internal.dispatch;

public interface DispatchFailureHandler<T> {
    /**
     * Called when a message could not be dispatched. This method can throw an exception to abort further dispatching.
     */
    void dispatchFailed(T message, Throwable failure);
}
