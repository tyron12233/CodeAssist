package com.tyron.builder.internal.dispatch;

/**
 * A sink for a bounded stream of messages.
 *
 * <p>Implementations are not required to be thread-safe.
 */
public interface BoundedDispatch<T> extends Dispatch<T>, StreamCompletion {
    /**
     * Signals the end of the stream of messages. No further messages should be dispatched using the {@link Dispatch#dispatch(Object)} method after this method is called.
     */
    @Override
    void endStream();
}
