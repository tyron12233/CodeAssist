package com.tyron.builder.process.internal.worker;

import com.tyron.builder.internal.remote.ObjectConnection;
import com.tyron.builder.internal.service.ServiceRegistry;

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
