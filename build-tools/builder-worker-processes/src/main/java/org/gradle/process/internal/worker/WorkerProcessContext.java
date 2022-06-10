package org.gradle.process.internal.worker;

import org.gradle.internal.remote.ObjectConnection;
import org.gradle.internal.service.ServiceRegistry;

public interface WorkerProcessContext {
    /**
     * Returns the unique identifier for this worker process.
     */
    Object getWorkerId();

    /**
     * Returns a display name for this worker process.
     */
    String getDisplayName();

    /**
     * Returns the connection which can be used to send/receive messages to/from the server process. Call {@link ObjectConnection#connect()} to complete the connection.
     */
    ObjectConnection getServerConnection();

    ClassLoader getApplicationClassLoader();

    ServiceRegistry getServiceRegistry();
}
