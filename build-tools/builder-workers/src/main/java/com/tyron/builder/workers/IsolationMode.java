package com.tyron.builder.workers;

/**
 * Isolation mode for workers.
 *
 * @since 4.0
 */
@Deprecated
public enum IsolationMode {
    /**
     * Let Gradle decide, this is the default.
     */
    AUTO,
    /**
     * Don't attempt to isolate the work, use in-process workers.
     */
    NONE,
    /**
     * Isolate the work in it's own classloader, use in-process workers.
     */
    CLASSLOADER,
    /**
     * Isolate the work in a separate process, use out-of-process workers.
     */
    PROCESS
}
