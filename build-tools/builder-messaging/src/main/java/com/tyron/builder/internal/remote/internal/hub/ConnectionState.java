package com.tyron.builder.internal.remote.internal.hub;

import com.tyron.builder.internal.remote.internal.RemoteConnection;
import com.tyron.builder.internal.remote.internal.hub.protocol.InterHubMessage;
import com.tyron.builder.internal.remote.internal.hub.queue.EndPointQueue;

class ConnectionState {
    private boolean receiveFinished;
    private boolean dispatchFinished;
    private final RemoteConnection<InterHubMessage> connection;
    private final ConnectionSet owner;
    private final EndPointQueue dispatchQueue;

    ConnectionState(ConnectionSet owner, RemoteConnection<InterHubMessage> connection, EndPointQueue dispatchQueue) {
        this.owner = owner;
        this.connection = connection;
        this.dispatchQueue = dispatchQueue;
    }

    public RemoteConnection<InterHubMessage> getConnection() {
        return connection;
    }

    public EndPointQueue getDispatchQueue() {
        return dispatchQueue;
    }

    public void receiveFinished() {
        receiveFinished = true;
        if (!dispatchFinished) {
            dispatchQueue.stop();
        }
        maybeDisconnected();
    }

    public void dispatchFinished() {
        dispatchFinished = true;
        maybeDisconnected();
    }

    private void maybeDisconnected() {
        if (dispatchFinished && receiveFinished) {
            owner.finished(this);
        }
    }
}
