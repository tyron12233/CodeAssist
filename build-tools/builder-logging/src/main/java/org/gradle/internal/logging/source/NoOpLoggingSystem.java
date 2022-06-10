package org.gradle.internal.logging.source;

import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.config.LoggingSourceSystem;


public class NoOpLoggingSystem implements StdOutLoggingSystem, StdErrLoggingSystem, LoggingSourceSystem {
    @Override
    public Snapshot snapshot() {
        return dummy();
    }

    @Override
    public Snapshot setLevel(LogLevel logLevel) {
        return dummy();
    }

    @Override
    public Snapshot startCapture() {
        return dummy();
    }

    @Override
    public void restore(Snapshot state) {}

    private Snapshot dummy() {
        return new Snapshot() {};
    }
}

