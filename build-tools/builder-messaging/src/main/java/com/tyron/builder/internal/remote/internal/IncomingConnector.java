package com.tyron.builder.internal.remote.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.remote.ConnectionAcceptor;

public interface IncomingConnector {
    /**
     * Starts listening for incoming connections. Assigns an arbitrary address for the endpoint.
     *
     * @param action the action to execute on incoming connection. The supplied action is not required to be thread-safe.
     * @param allowRemote If true, only allow connections from remote machines. If false, allow only from the local machine.
     * @return the address of the endpoint which the connector is listening on.
     */
    ConnectionAcceptor accept(Action<ConnectCompletion> action, boolean allowRemote);
}