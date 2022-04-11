package com.tyron.builder.internal.logging.config;

import com.tyron.builder.api.logging.LogLevel;

/**
 * Adapts a {@link LoggingConfigurer} to a {@link LoggingSourceSystem}.
 */
public class LoggingSystemAdapter implements LoggingSourceSystem {
    private final LoggingConfigurer configurer;
    private boolean enabled;
    private LogLevel logLevel = LogLevel.LIFECYCLE;

    public LoggingSystemAdapter(LoggingConfigurer configurer) {
        this.configurer = configurer;
    }

    @Override
    public Snapshot snapshot() {
        return new SnapshotImpl(enabled, logLevel);
    }

    @Override
    public Snapshot setLevel(LogLevel logLevel) {
        Snapshot snapshot = snapshot();
        if (this.logLevel != logLevel) {
            this.logLevel = logLevel;
            if (enabled) {
                configurer.configure(logLevel);
            }
        }
        return snapshot;
    }

    @Override
    public Snapshot startCapture() {
        Snapshot snapshot = snapshot();
        if (!enabled) {
            enabled = true;
            configurer.configure(logLevel);
        }
        return snapshot;
    }

    @Override
    public void restore(Snapshot state) {
        SnapshotImpl snapshot = (SnapshotImpl) state;
        logLevel = snapshot.level;
        enabled = snapshot.enabled;
        configurer.configure(logLevel);
    }

    private static class SnapshotImpl implements Snapshot {
        private final boolean enabled;
        private final LogLevel level;

        SnapshotImpl(boolean enabled, LogLevel level) {
            this.enabled = enabled;
            this.level = level;
        }
    }
}

