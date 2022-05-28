package com.tyron.builder.internal.remote.internal.hub;

import com.tyron.builder.internal.remote.internal.RemoteConnection;
import com.tyron.builder.internal.remote.internal.hub.protocol.EndOfStream;
import com.tyron.builder.internal.remote.internal.hub.protocol.InterHubMessage;
import com.tyron.builder.internal.remote.internal.hub.queue.EndPointQueue;

import java.util.HashSet;
import java.util.Set;

class ConnectionSet {
    private final Set<ConnectionState> connections = new HashSet<ConnectionState>();
    private final IncomingQueue incomingQueue;
    private final OutgoingQueue outgoingQueue;
    private boolean stopping;

    ConnectionSet(IncomingQueue incomingQueue, OutgoingQueue outgoingQueue) {
        this.incomingQueue = incomingQueue;
        this.outgoingQueue = outgoingQueue;
    }

    /**
     * Adds a new incoming connection.
     */
    public ConnectionState add(RemoteConnection<InterHubMessage> connection) {
        EndPointQueue queue = outgoingQueue.newEndpoint();
        ConnectionState state = new ConnectionState(this, connection, queue);
        connections.add(state);
        return state;
    }

    /**
     * Called when all dispatch and receive has completed on the given connection.
     */
    public void finished(ConnectionState connectionState) {
        connections.remove(connectionState);
        if (stopping) {
            maybeStop();
        }
    }

    /**
     * Called when no further incoming connections will be added.
     */
    public void noFurtherConnections() {
        stopping = true;
        maybeStop();
    }

    private void maybeStop() {
        if (connections.isEmpty()) {
            outgoingQueue.discardQueued();
            incomingQueue.queue(new EndOfStream());
        }
    }
}
