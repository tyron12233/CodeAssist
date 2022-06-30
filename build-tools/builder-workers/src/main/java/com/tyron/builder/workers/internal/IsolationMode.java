package com.tyron.builder.workers.internal;

/**
 * Isolation mode for workers.
 */
public enum IsolationMode {
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
