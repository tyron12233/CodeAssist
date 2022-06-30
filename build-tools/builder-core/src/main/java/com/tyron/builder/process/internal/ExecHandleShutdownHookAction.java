package com.tyron.builder.process.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Terminates the external running 'sub' process when the Gradle process is being cancelled.
 */
public class ExecHandleShutdownHookAction implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecHandleShutdownHookAction.class);
    private final ExecHandle execHandle;

    public ExecHandleShutdownHookAction(ExecHandle execHandle) {
        if (execHandle == null) {
            throw new IllegalArgumentException("execHandle is null!");
        }

        this.execHandle = execHandle;
    }

    @Override
    public void run() {
        try {
            execHandle.abort();
        } catch (Throwable t) {
            LOGGER.error("failed to abort " + execHandle, t);
        }
    }
}
