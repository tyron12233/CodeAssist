package com.tyron.builder.workers;

/**
 * Forking mode for workers.
 *
 * @since 3.5
 */
@Deprecated
public enum ForkMode {
    /**
     * Let Gradle decide, this is the default.
     */
    AUTO,
    /**
     * Never fork, aka. use in-process workers.
     */
    NEVER,
    /**
     * Always fork, aka. use out-of-process workers.
     */
    ALWAYS;
}
