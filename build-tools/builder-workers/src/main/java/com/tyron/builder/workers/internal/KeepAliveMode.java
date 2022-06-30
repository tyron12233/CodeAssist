package com.tyron.builder.workers.internal;

public enum KeepAliveMode {
    /**
     * Keep alive until the end of the build session
     */
    SESSION,
    /**
     * Keep alive until the daemon stops
     */
    DAEMON
}
