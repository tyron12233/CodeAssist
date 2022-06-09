package com.tyron.builder.internal.remote;

import com.tyron.builder.api.Action;

/**
 * A {@code MessagingServer} allows the creation of multiple bi-directional uni-cast connections.
 */
public interface MessagingServer {
    /**
     * Creates an endpoint that peers can connect to. Assigns an arbitrary address.
     *
     * @param action The action to execute when a connection has been established.
     * @return The local address of the endpoint, for the peer to connect to.
     */
    ConnectionAcceptor accept(Action<ObjectConnection> action);
}
