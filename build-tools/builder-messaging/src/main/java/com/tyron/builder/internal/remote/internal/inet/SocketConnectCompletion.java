package com.tyron.builder.internal.remote.internal.inet;

import com.tyron.builder.internal.remote.internal.KryoBackedMessageSerializer;
import com.tyron.builder.internal.remote.internal.RemoteConnection;
import com.tyron.builder.internal.serialize.StatefulSerializer;
import com.tyron.builder.internal.remote.internal.ConnectCompletion;

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
