package com.tyron.builder.internal.dispatch;

import javax.annotation.Nullable;

/**
 * A source for messages. Implementations do not have to be thread-safe.
 */
public interface Receive<T> {
    /**
     * Blocks until the next message is available. Returns null when the end of the message stream has been reached.
     *
     * @return The next message, or null when the end of the stream has been reached.
     */
    @Nullable
    T receive();
}
