package org.gradle.internal.remote.internal;

import org.gradle.internal.remote.Address;

public interface OutgoingConnector {
    /**
     * Creates a connection to the given address. Blocks until the connection with the peer has been established.
     *
     * @throws ConnectException when there is nothing listening on the remote address.
     */
    ConnectCompletion connect(Address destinationAddress) throws ConnectException;
}
