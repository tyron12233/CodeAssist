package org.gradle.internal.remote.internal.inet;

import org.gradle.internal.remote.internal.KryoBackedMessageSerializer;
import org.gradle.internal.remote.internal.RemoteConnection;
import org.gradle.internal.serialize.StatefulSerializer;
import org.gradle.internal.remote.internal.ConnectCompletion;

import java.nio.channels.SocketChannel;

class SocketConnectCompletion implements ConnectCompletion {
    private final SocketChannel socket;

    public SocketConnectCompletion(SocketChannel socket) {
        this.socket = socket;
    }

    @Override
    public String toString() {
        return socket.socket().getLocalSocketAddress() + " to " + socket.socket().getRemoteSocketAddress();
    }

    @Override
    public <T> RemoteConnection<T> create(StatefulSerializer<T> serializer) {
        return new SocketConnection<T>(socket, new KryoBackedMessageSerializer(), serializer);
    }
}
