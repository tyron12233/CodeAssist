package com.tyron.builder.internal.remote.services;

import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.id.IdGenerator;
import com.tyron.builder.internal.id.UUIDGenerator;
import com.tyron.builder.internal.remote.MessagingClient;
import com.tyron.builder.internal.remote.MessagingServer;
import com.tyron.builder.internal.remote.internal.IncomingConnector;
import com.tyron.builder.internal.remote.internal.OutgoingConnector;
import com.tyron.builder.internal.remote.internal.hub.MessageHubBackedClient;
import com.tyron.builder.internal.remote.internal.hub.MessageHubBackedServer;
import com.tyron.builder.internal.remote.internal.inet.InetAddressFactory;
import com.tyron.builder.internal.remote.internal.inet.TcpIncomingConnector;
import com.tyron.builder.internal.remote.internal.inet.TcpOutgoingConnector;

import java.util.UUID;

/**
 * A factory for a set of messaging services. Provides the following services:
 *
 * <ul>
 *
 * <li>{@link MessagingClient}</li>
 *
 * <li>{@link MessagingServer}</li>
 *
 * </ul>
 */
public class MessagingServices {
    private final IdGenerator<UUID> idGenerator = new UUIDGenerator();

    protected InetAddressFactory createInetAddressFactory() {
        return new InetAddressFactory();
    }

    protected OutgoingConnector createOutgoingConnector() {
        return new TcpOutgoingConnector();
    }

    protected IncomingConnector createIncomingConnector(ExecutorFactory executorFactory, InetAddressFactory inetAddressFactory) {
        return new TcpIncomingConnector(
                executorFactory,
                inetAddressFactory,
                idGenerator
        );
    }

    protected MessagingClient createMessagingClient(OutgoingConnector outgoingConnector, ExecutorFactory executorFactory) {
        return new MessageHubBackedClient(
                outgoingConnector,
                executorFactory);
    }

    protected MessagingServer createMessagingServer(IncomingConnector incomingConnector, ExecutorFactory executorFactory) {
        return new MessageHubBackedServer(
                incomingConnector,
                executorFactory);
    }
}
