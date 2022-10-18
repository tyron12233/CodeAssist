package org.gradle.tooling.internal.provider;

import org.gradle.internal.concurrent.Stoppable;
import org.gradle.launcher.daemon.client.DaemonStartListener;
import org.gradle.launcher.daemon.client.DaemonStopClient;
import org.gradle.launcher.daemon.context.DaemonConnectDetails;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class ShutdownCoordinator implements DaemonStartListener, Stoppable {
    private final Set<DaemonConnectDetails> daemons = new CopyOnWriteArraySet<DaemonConnectDetails>();
    private final DaemonStopClient client;

    public ShutdownCoordinator(DaemonStopClient client) {
        this.client = client;
    }

    @Override
    public void daemonStarted(DaemonConnectDetails daemon) {
        daemons.add(daemon);
    }

    @Override
    public void stop() {
        client.gracefulStop(daemons);
    }
}