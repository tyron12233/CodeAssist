package org.gradle.launcher.daemon.configuration;

import java.io.File;
import java.util.List;

public class DefaultDaemonServerConfiguration implements DaemonServerConfiguration {

    private final String daemonUid;
    private final File daemonBaseDir;
    private final int idleTimeoutMs;
    private final int periodicCheckIntervalMs;
    private final boolean singleUse;
    private final DaemonParameters.Priority priority;
    private final List<String> jvmOptions;

    public DefaultDaemonServerConfiguration(String daemonUid, File daemonBaseDir, int idleTimeoutMs, int periodicCheckIntervalMs, boolean singleUse, DaemonParameters.Priority priority, List<String> jvmOptions) {
        this.daemonUid = daemonUid;
        this.daemonBaseDir = daemonBaseDir;
        this.idleTimeoutMs = idleTimeoutMs;
        this.periodicCheckIntervalMs = periodicCheckIntervalMs;
        this.singleUse = singleUse;
        this.priority = priority;
        this.jvmOptions = jvmOptions;
    }

    @Override
    public File getBaseDir() {
        return daemonBaseDir;
    }

    @Override
    public int getIdleTimeout() {
        return idleTimeoutMs;
    }

    @Override
    public int getPeriodicCheckIntervalMs() {
        return periodicCheckIntervalMs;
    }

    @Override
    public String getUid() {
        return daemonUid;
    }

    @Override
    public DaemonParameters.Priority getPriority() {
        return priority;
    }

    @Override
    public List<String> getJvmOptions() {
        return jvmOptions;
    }

    @Override
    public boolean isSingleUse() {
        return singleUse;
    }
}
