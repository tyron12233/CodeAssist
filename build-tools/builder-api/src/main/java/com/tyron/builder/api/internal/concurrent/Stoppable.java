package com.tyron.builder.api.internal.concurrent;

/**
 * Represents an object which performs concurrent activity.
 */
public interface Stoppable {
    /**
     * <p>Requests a graceful stop of this object. Blocks until all concurrent activity has been completed.</p>
     *
     * <p>If this object has already been stopped, this method does nothing.</p>
     */
    void stop();
}