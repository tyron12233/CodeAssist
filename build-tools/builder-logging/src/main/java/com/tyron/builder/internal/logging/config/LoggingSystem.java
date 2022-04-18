package com.tyron.builder.internal.logging.config;

/**
 * Some configurable logging system, whose state can be snapshot, mutated and restored.
 */
public interface LoggingSystem {
    /**
     * Snapshots the current configuration state of this logging system.
     */
    Snapshot snapshot();

    /**
     * Resets this logging system to some previous configuration state.
     */
    void restore(Snapshot state);

    interface Snapshot {
    }
}

