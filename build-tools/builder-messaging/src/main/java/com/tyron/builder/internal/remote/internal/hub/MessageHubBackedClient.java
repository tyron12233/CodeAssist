package com.tyron.builder.internal.remote.internal.hub;

import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.remote.Address;
import com.tyron.builder.internal.remote.internal.OutgoingConnector;
import com.tyron.builder.internal.remote.MessagingClient;
import com.tyron.builder.internal.remote.ObjectConnection;

public class MessageHubBackedClient implements MessagingClient {
    private final OutgoingConnector connector;
    private final ExecutorFactory executorFactory;

    public MessageHubBackedClient(OutgoingConnector connector, ExecutorFactory executorFactory) {
        this.connector = connector;
        this.executorFactory = executorFactory;
    }

    @Override
    public ObjectConnection getConnection(Address address) {
        return new MessageHubBackedObjectConnection(executorFactory, connector.connect(address));
    }
}
