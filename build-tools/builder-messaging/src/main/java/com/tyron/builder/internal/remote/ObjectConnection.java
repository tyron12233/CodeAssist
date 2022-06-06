package com.tyron.builder.internal.remote;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.concurrent.AsyncStoppable;

/**
 * Manages a set of incoming and outgoing channels between 2 peers.
 *
 * NOTE: This contract guarantees only partial thread-safety. Configuration and {@link #connect()} are not thread-safe and must be performed by the same thread,
 * generally some configuration thread. Only the stop methods are thread-safe. The other methods will be made thread-safe (or moved somewhere else) later.
 */
public interface ObjectConnection extends AsyncStoppable, ObjectConnectionBuilder {
    /**
     * Completes the connection. No further configuration can be done.
     */
    void connect();

    /**
     * Commences a graceful stop of this connection. Stops accepting outgoing messages. Requests that the peer stop
     * sending incoming messages.
     */
    @Override
    void requestStop();

    /**
     * Performs a graceful stop of this connection. Stops accepting outgoing messages. Blocks until all incoming messages
     * have been handled, and all outgoing messages have been forwarded to the peer.
     */
    @Override
    void stop();

    /**
     * Indicate that the execution containing this {@code ObjectConnection} has been prematurely stopped.
     */
    void abort();

    /**
     * Add a callback upon unrecoverable errors, e.g. broken connection. Should not throw any exceptions because
     * this is the last line of defense.
     * @param handler the callback
     */
    void addUnrecoverableErrorHandler(Action<Throwable> handler);
}
