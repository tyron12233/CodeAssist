package org.gradle.internal.remote.services;

import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.id.UUIDGenerator;
import org.gradle.internal.remote.MessagingClient;
import org.gradle.internal.remote.MessagingServer;
import org.gradle.internal.remote.internal.IncomingConnector;
import org.gradle.internal.remote.internal.OutgoingConnector;
import org.gradle.internal.remote.internal.hub.MessageHubBackedClient;
import org.gradle.internal.remote.internal.hub.MessageHubBackedServer;
import org.gradle.internal.remote.internal.inet.InetAddressFactory;
import org.gradle.internal.remote.internal.inet.TcpIncomingConnector;
import org.gradle.internal.remote.internal.inet.TcpOutgoingConnector;

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
