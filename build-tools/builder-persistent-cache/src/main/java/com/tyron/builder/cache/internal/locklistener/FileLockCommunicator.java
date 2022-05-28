package com.tyron.builder.cache.internal.locklistener;

import static com.tyron.builder.internal.UncheckedException.throwAsUncheckedException;
import static com.tyron.builder.cache.internal.locklistener.FileLockPacketType.LOCK_RELEASE_CONFIRMATION;
import static com.tyron.builder.cache.internal.locklistener.FileLockPacketType.UNLOCK_REQUEST;
import static com.tyron.builder.cache.internal.locklistener.FileLockPacketType.UNLOCK_REQUEST_CONFIRMATION;

import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.remote.internal.inet.InetAddressFactory;
import com.tyron.common.logging.IdeLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Set;
import java.util.logging.Logger;


public class FileLockCommunicator {
    private static final Logger LOGGER = IdeLog.getCurrentLogger(FileLockCommunicator.class);
    private static final String SOCKET_OPERATION_NOT_PERMITTED_ERROR_MESSAGE = "Operation not permitted";
    private static final String SOCKET_NETWORK_UNREACHABLE_ERROR_MESSAGE = "Network is unreachable";
    private static final String SOCKET_CANNOT_ASSIGN_ADDRESS_ERROR_MESSAGE = "Cannot assign requested address";

    private final DatagramSocket socket;
    private final InetAddressFactory addressFactory;
    private volatile boolean stopped;

    public FileLockCommunicator(InetAddressFactory addressFactory) {
        this.addressFactory = addressFactory;
        try {
            socket = new DatagramSocket(0, addressFactory.getWildcardBindingAddress());
        } catch (SocketException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    public boolean pingOwner(int ownerPort, long lockId, String displayName) {
        boolean pingSentSuccessfully = false;
        try {
            byte[] bytesToSend = FileLockPacketPayload.encode(lockId, UNLOCK_REQUEST);
            for (InetAddress address : addressFactory.getCommunicationAddresses()) {
                try {
                    socket.send(new DatagramPacket(bytesToSend, bytesToSend.length, address, ownerPort));
                    pingSentSuccessfully = true;
                } catch (IOException e) {
                    String message = e.getMessage();
                    if (message != null && (
                            message.startsWith(SOCKET_OPERATION_NOT_PERMITTED_ERROR_MESSAGE)
                            || message.startsWith(SOCKET_NETWORK_UNREACHABLE_ERROR_MESSAGE)
                            || message.startsWith(SOCKET_CANNOT_ASSIGN_ADDRESS_ERROR_MESSAGE)
                    )) {
                        LOGGER.info("Failed attempt to ping owner of lock for " + displayName + " (lock id: " + lockId + ", port: " + ownerPort + ", address: " + address + ")");
                    } else {
                        throw e;
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to ping owner of lock for %s (lock id: %s, port: %s)", displayName, lockId, ownerPort), e);
        }
        return pingSentSuccessfully;
    }

    public DatagramPacket receive() throws GracefullyStoppedException {
        try {
            byte[] bytes = new byte[FileLockPacketPayload.MAX_BYTES];
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            socket.receive(packet);
            return packet;
        } catch (IOException e) {
            if (!stopped) {
                throw new RuntimeException(e);
            }
            throw new GracefullyStoppedException();
        }
    }

    public FileLockPacketPayload decode(DatagramPacket receivedPacket) {
        try {
            return FileLockPacketPayload.decode(receivedPacket.getData(), receivedPacket.getLength());
        } catch (IOException e) {
            if (!stopped) {
                throw new RuntimeException(e);
            }
            throw new GracefullyStoppedException();
        }
    }

    public void confirmUnlockRequest(SocketAddress address, long lockId) {
        try {
            byte[] bytes = FileLockPacketPayload.encode(lockId, UNLOCK_REQUEST_CONFIRMATION);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            packet.setSocketAddress(address);
            socket.send(packet);
        } catch (IOException e) {
            if (!stopped) {
                throw new RuntimeException(e);
            }
            throw new GracefullyStoppedException();
        }
    }

    public void confirmLockRelease(Set<SocketAddress> addresses, long lockId) {
        byte[] bytes = FileLockPacketPayload.encode(lockId, LOCK_RELEASE_CONFIRMATION);
        for (SocketAddress address : addresses) {
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            packet.setSocketAddress(address);
            LOGGER.info("Confirming lock release to Gradle process at port " + packet.getPort() + " for lock with id " + lockId);
            try {
                socket.send(packet);
            } catch (IOException e) {
                if (!stopped) {
                    LOGGER.info("Failed to confirm lock release to Gradle process at port " + packet.getPort() + " for lock with id " + lockId);
                }
            }
        }
    }

    public void stop() {
        stopped = true;
        socket.close();
    }

    public int getPort() {
        return socket.getLocalPort();
    }
}
