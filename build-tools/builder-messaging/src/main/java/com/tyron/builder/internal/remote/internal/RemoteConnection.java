package com.tyron.builder.internal.remote.internal;

import javax.annotation.Nullable;

/**
 * <p>A messaging end-point with some remote, or otherwise unreliable, peer.</p>
 *
 * <p>This interface simply specializes the exceptions thrown by the methods of this connection.</p>
 */
public interface RemoteConnection<T> extends Connection<T> {
    /**
     * {@inheritDoc}
     *
     * @throws MessageIOException On failure to dispatch the message to the peer.
     */
    @Override
    void dispatch(T message) throws MessageIOException;

    void flush() throws MessageIOException;

    /**
     * {@inheritDoc}
     * @throws MessageIOException On failure to receive the message from the peer.
     */
    @Override
    @Nullable
    T receive() throws MessageIOException;
}