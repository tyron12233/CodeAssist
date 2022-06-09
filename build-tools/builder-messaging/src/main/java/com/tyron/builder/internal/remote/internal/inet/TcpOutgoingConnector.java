package com.tyron.builder.internal.remote.internal.inet;

import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.remote.Address;
import com.tyron.builder.internal.remote.internal.ConnectCompletion;
import com.tyron.builder.internal.remote.internal.OutgoingConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.List;

public class TcpOutgoingConnector implements OutgoingConnector {
    private static final Logger LOGGER = LoggerFactory.getLogger(TcpOutgoingConnector.class);
    private static final int CONNECT_TIMEOUT = 10000;

    @Override
    public ConnectCompletion connect(Address destinationAddress) throws com.tyron.builder.internal.remote.internal.ConnectException {
        if (!(destinationAddress instanceof InetEndpoint)) {
            throw new IllegalArgumentException(String.format("Cannot create a connection to address of unknown type: %s.", destinationAddress));
        }
        InetEndpoint address = (InetEndpoint) destinationAddress;
        LOGGER.debug("Attempting to connect to {}.", address);

        // Try each address in turn. Not all of them are necessarily reachable (eg when socket option IPV6_V6ONLY
        // is on - the default for debian and others), so we will try each of them until we can connect
        List<InetAddress> candidateAddresses = address.getCandidates();

        // Now try each address
        try {
            Exception lastFailure = null;
            for (InetAddress candidate : candidateAddresses) {
                LOGGER.debug("Trying to connect to address {}.", candidate);
                SocketChannel socketChannel;
                try {
                    socketChannel = tryConnect(address, candidate);
                } catch (SocketException e) {
                    LOGGER.debug("Cannot connect to address {}, skipping.", candidate);
                    lastFailure = e;
                    continue;
                } catch (SocketTimeoutException e) {
                    LOGGER.debug("Timeout connecting to address {}, skipping.", candidate);
                    lastFailure = e;
                    continue;
                }
                LOGGER.debug("Connected to address {}.", socketChannel.socket().getRemoteSocketAddress());
                return new SocketConnectCompletion(socketChannel);
            }
            throw new com.tyron.builder.internal.remote.internal.ConnectException(String.format("Could not connect to server %s. Tried addresses: %s.",
                    destinationAddress, candidateAddresses), lastFailure);
        } catch (com.tyron.builder.internal.remote.internal.ConnectException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(String.format("Could not connect to server %s. Tried addresses: %s.",
                    destinationAddress, candidateAddresses), e);
        }
    }

    private SocketChannel tryConnect(InetEndpoint address, InetAddress candidate) throws IOException {
        SocketChannel socketChannel = SocketChannel.open();

        try {
            socketChannel.socket().connect(new InetSocketAddress(candidate, address.getPort()), CONNECT_TIMEOUT);

            if (!detectSelfConnect(socketChannel)) {
                return socketChannel;
            }
            socketChannel.close();
        } catch (IOException e) {
            socketChannel.close();
            throw e;
        } catch (Throwable e) {
            socketChannel.close();
            throw UncheckedException.throwAsUncheckedException(e);
        }

        throw new java.net.ConnectException(String.format("Socket connected to itself on %s port %s.", candidate, address.getPort()));
    }

    boolean detectSelfConnect(SocketChannel socketChannel) {
        Socket socket = socketChannel.socket();
        SocketAddress localAddress = socket.getLocalSocketAddress();
        SocketAddress remoteAddress = socket.getRemoteSocketAddress();
        if (localAddress.equals(remoteAddress)) {
            LOGGER.debug("Detected that socket was bound to {} while connecting to {}. This looks like the socket connected to itself.",
                localAddress, remoteAddress);
            return true;
        }
        return false;
    }
}
