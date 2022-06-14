package com.tyron.builder.process.internal.worker;

import com.tyron.builder.internal.remote.ObjectConnection;
import com.tyron.builder.process.ExecResult;
import com.tyron.builder.process.internal.health.memory.JvmMemoryStatus;

/**
 * A child JVM that performs some worker action. You can send and receive messages to/from the worker action
 * using a supplied {@link ObjectConnection}.
 */
public interface WorkerProcess {
    WorkerProcess start();

    /**
     * The connection to the worker. Call {@link ObjectConnection#connect()} to complete the connection.
     */
    ObjectConnection getConnection();

    ExecResult waitForStop();

    JvmMemoryStatus getJvmMemoryStatus();

    /**
     * Stop the associated process without expecting a normal exit value.
     */
    void stopNow();
}
