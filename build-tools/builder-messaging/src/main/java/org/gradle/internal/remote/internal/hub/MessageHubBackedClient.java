package org.gradle.internal.remote.internal.hub;

import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.remote.Address;
import org.gradle.internal.remote.internal.OutgoingConnector;
import org.gradle.internal.remote.MessagingClient;
import org.gradle.internal.remote.ObjectConnection;

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
