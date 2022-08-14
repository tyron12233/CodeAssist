package org.gradle.launcher.daemon.context;

import org.gradle.internal.remote.Address;

/**
 * Data to identify and connect to a daemon.
 */
public interface DaemonConnectDetails {
    String getUid();

    Long getPid();

    Address getAddress();

    byte[] getToken();
}
