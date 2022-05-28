package com.tyron.builder.internal.remote;

/**
 * A {@code MessagingClient} maintains a single bi-directional uni-cast object connection with some peer.
 */
public interface MessagingClient {
    /**
     * Creates a connection to the given address. Blocks until the connection has been established.
     *
     * @param address The address to connect to.
     */
    ObjectConnection getConnection(Address address);
}
