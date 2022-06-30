package com.tyron.builder.api.tasks;

/**
 * Provides information about some work which was performed.
 */
public interface WorkResult {
    boolean getDidWork();

    /**
     * Returns this result if it did work, otherwise returns the result given as a parameter.
     *
     * @since 6.0
     */
    default WorkResult or(WorkResult other) {
        if (!getDidWork()) {
            return other;
        } else {
            return this;
        }
    }
}