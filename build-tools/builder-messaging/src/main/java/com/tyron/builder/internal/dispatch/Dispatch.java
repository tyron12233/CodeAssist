package com.tyron.builder.internal.dispatch;

/**
 * A general purpose sink for a stream of messages.
 *
 * <p>Implementations are not required to be thread-safe.
 */
public interface Dispatch<T> {
    /**
     * Dispatches the next message. Blocks until the messages has been accepted but generally does not wait for the
     * message to be processed. Delivery guarantees are implementation specific.
     *
     * @param message The message.
     */
    void dispatch(T message);
}