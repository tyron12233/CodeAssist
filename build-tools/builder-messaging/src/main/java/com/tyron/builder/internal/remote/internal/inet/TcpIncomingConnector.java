package com.tyron.builder.internal.remote.internal.inet;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.concurrent.ManagedExecutor;
import com.tyron.builder.internal.id.IdGenerator;
import com.tyron.builder.internal.remote.Address;
import com.tyron.builder.internal.remote.ConnectionAcceptor;
import com.tyron.builder.internal.remote.internal.ConnectCompletion;
import com.tyron.builder.internal.remote.internal.IncomingConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class TcpIncomingConnector implements IncomingConnector {
    private static final Logger LOGGER = LoggerFactory.getLogger(TcpIncomingConnector.class);
    private final ExecutorFactory executorFactory;
    private final InetAddressFactory addressFactory;
    private final IdGenerator<UUID> idGenerator;

    public TcpIncomingConnector(ExecutorFactory executorFactory, InetAddressFactory addressFactory, IdGenerator<UUID> idGenerator) {
        this.executorFactory = executorFactory;
        this.addressFactory = addressFactory;
        this.idGenerator = idGenerator;
    }

    @Override
    public ConnectionAcceptor accept(Action<ConnectCompletion> action, boolean allowRemote) {
        final ServerSocketChannel serverSocket;
        int localPort;
        try {
            serverSocket = ServerSocketChannel.open();
            serverSocket.socket().bind(new InetSocketAddress(addressFactory.getLocalBindingAddress(), 0));
            localPort = serverSocket.socket().getLocalPort();
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }

        UUID id = idGenerator.generateId();
        List<InetAddress> addresses = Collections.singletonList(addressFactory.getLocalBindingAddress());
        final Address address = new MultiChoiceAddress(id, localPort, addresses);
        LOGGER.debug("Listening on {}.", address);

        final ManagedExecutor executor = executorFactory.create("Incoming " + (allowRemote ? "remote" : "local")+ " TCP Connector on port " + localPort);
        executor.execute(new Receiver(serverSocket, action, allowRemote));

        return new ConnectionAcceptor() {
            @Override
            public Address getAddress() {
                return address;
            }

            @Override
            public void requestStop() {
                CompositeStoppable.stoppable(serverSocket).stop();
            }

            @Override
            public void stop() {
                requestStop();
                executor.stop();
            }
        };
    }

    private class Receiver implements Runnable {
        private final ServerSocketChannel serverSocket;
        private final Action<ConnectCompletion> action;
        private final boolean allowRemote;

        public Receiver(ServerSocketChannel serverSocket, Action<ConnectCompletion> action, boolean allowRemote) {
            this.serverSocket = serverSocket;
            this.action = action;
            this.allowRemote = allowRemote;
        }

        @Override
        public void run() {
            try {
                try {
                    while (true) {
                        final SocketChannel socket = serverSocket.accept();
                        InetSocketAddress remoteSocketAddress = (InetSocketAddress) socket.socket().getRemoteSocketAddress();
                        InetAddress remoteInetAddress = remoteSocketAddress.getAddress();
                        if (!allowRemote && !addressFactory.isCommunicationAddress(remoteInetAddress)) {
                            LOGGER.error("Cannot accept connection from remote address {}.", remoteInetAddress);
                            socket.close();
                            continue;
                        }
                        LOGGER.debug("Accepted connection from {} to {}.", socket.socket().getRemoteSocketAddress(), socket.socket().getLocalSocketAddress());
                        try {
                            action.execute(new SocketConnectCompletion(socket));
                        } catch (Throwable t) {
                            socket.close();
                            throw t;
                        }
                    }
                } catch (ClosedChannelException e) {
                    // Ignore
                } catch (Throwable e) {
                    LOGGER.error("Could not accept remote connection.", e);
                }
            } finally {
                CompositeStoppable.stoppable(serverSocket).stop();
            }
        }

    }

}
