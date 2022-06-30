package com.tyron.builder.internal.remote.internal.inet;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides information on how two processes on this machine can communicate via IP addresses
 */
public class InetAddressFactory {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Object lock = new Object();
    private List<InetAddress> communicationAddresses;
    private InetAddress localBindingAddress;
    private InetAddress wildcardBindingAddress;
    private InetAddresses inetAddresses;
    private boolean initialized;

    /**
     * Determines if the IP address can be used for communication with this machine
     */
    public boolean isCommunicationAddress(InetAddress address) {
        try {
            synchronized (lock) {
                init();
                return communicationAddresses.contains(address);
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not determine the IP addresses for this machine.", e);
        }
    }

    /**
     * Locates the possible IP addresses which can be used to communicate with this machine.
     *
     * Loopback addresses are preferred.
     */
    public List<InetAddress> getCommunicationAddresses() {
        try {
            synchronized (lock) {
                init();
                return communicationAddresses;
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not determine the local IP addresses for this machine.", e);
        }
    }

    public InetAddress getLocalBindingAddress() {
        try {
            synchronized (lock) {
                init();
                return localBindingAddress;
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not determine a usable local IP for this machine.", e);
        }
    }

    public InetAddress getWildcardBindingAddress() {
        try {
            synchronized (lock) {
                init();
                return wildcardBindingAddress;
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not determine a usable wildcard IP for this machine.", e);
        }
    }

    private void init() throws Exception {
        if (initialized) {
            return;
        }

        initialized = true;
        if (inetAddresses == null) { // For testing
            inetAddresses = new InetAddresses();
        }

        wildcardBindingAddress = new InetSocketAddress(0).getAddress();

        findLocalBindingAddress();

        findCommunicationAddresses();

        handleOpenshift();
    }

    /**
     * Prefer first loopback address if available, otherwise use the wildcard address.
     */
    private void findLocalBindingAddress() {
        if (inetAddresses.getLoopback().isEmpty()) {
            logger.debug("No loopback address for local binding, using fallback " + wildcardBindingAddress);
            localBindingAddress = wildcardBindingAddress;
        } else {
            localBindingAddress = InetAddress.getLoopbackAddress();
        }
    }

    private void handleOpenshift() {
        InetAddress openshiftBindAddress = findOpenshiftAddresses();
        if (openshiftBindAddress != null) {
            localBindingAddress = openshiftBindAddress;
            communicationAddresses.add(openshiftBindAddress);
        }
    }

    @Nullable
    private InetAddress findOpenshiftAddresses() {
        for (String key : System.getenv().keySet()) {
            if (key.startsWith("OPENSHIFT_") && key.endsWith("_IP")) {
                String ipAddress = System.getenv(key);
                logger.debug("OPENSHIFT IP environment variable " + key + " detected. Using IP address " + ipAddress);
                try {
                    return InetAddress.getByName(ipAddress);
                } catch (UnknownHostException e) {
                    throw new RuntimeException(String.format("Unable to use OPENSHIFT IP - invalid IP address '%s' specified in environment variable %s.", ipAddress, key), e);
                }
            }
        }
        return null;
    }

    private void findCommunicationAddresses() throws UnknownHostException {
        communicationAddresses = new ArrayList<InetAddress>();
        if (inetAddresses.getLoopback().isEmpty()) {
            if (inetAddresses.getRemote().isEmpty()) {
                InetAddress fallback = InetAddress.getByName(null);
                logger.debug("No loopback addresses for communication, using fallback " + fallback);
                communicationAddresses.add(fallback);
            } else {
                logger.debug("No loopback addresses for communication, using remote addresses instead.");
                communicationAddresses.addAll(inetAddresses.getRemote());
            }
        } else {
            communicationAddresses.addAll(inetAddresses.getLoopback());
        }
    }
}