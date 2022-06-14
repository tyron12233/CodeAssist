package com.tyron.builder.internal.remote.internal.inet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Provides some information about the network addresses of the local machine.
 */
class InetAddresses {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final List<InetAddress> loopback = new ArrayList<InetAddress>();
    private final List<InetAddress> remote = new ArrayList<InetAddress>();

    InetAddresses() throws SocketException {
        analyzeNetworkInterfaces();
    }

    private void analyzeNetworkInterfaces() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) {
            while (interfaces.hasMoreElements()) {
                analyzeNetworkInterface(interfaces.nextElement());
            }
        }
    }

    private void analyzeNetworkInterface(NetworkInterface networkInterface) {
        logger.debug("Adding IP addresses for network interface " + networkInterface.getDisplayName());
        try {
            boolean isLoopbackInterface = networkInterface.isLoopback();
            logger.debug("Is this a loopback interface? " + isLoopbackInterface);

            Enumeration<InetAddress> candidates = networkInterface.getInetAddresses();
            while (candidates.hasMoreElements()) {
                InetAddress candidate = candidates.nextElement();
                if (isLoopbackInterface) {
                    if (candidate.isLoopbackAddress()) {
                        logger.debug("Adding loopback address " + candidate);
                        loopback.add(candidate);
                    } else {
                        logger.debug("Ignoring remote address on loopback interface " + candidate);
                    }
                } else {
                    if (candidate.isLoopbackAddress()) {
                        logger.debug("Ignoring loopback address on remote interface " + candidate);
                    } else {
                        logger.debug("Adding remote address " + candidate);
                        remote.add(candidate);
                    }
                }
            }
        } catch (SocketException e) {
            // Log the error but analyze the remaining interfaces. We could for example run into https://bugs.openjdk.java.net/browse/JDK-7032558
            logger.error("Error while querying interface " +
                        networkInterface +
                        " for IP addresses. " +
                        e);
        } catch (Throwable e) {
            throw new RuntimeException(String.format("Could not determine the IP addresses for network interface %s",
                    networkInterface.getName()), e);
        }
    }

    public List<InetAddress> getLoopback() {
        return loopback;
    }

    public List<InetAddress> getRemote() {
        return remote;
    }
}