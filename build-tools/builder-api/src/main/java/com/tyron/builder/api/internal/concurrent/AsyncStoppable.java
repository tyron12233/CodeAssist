package com.tyron.builder.api.internal.concurrent;

/**
 * A {@link Stoppable} object whose stop process can be performed asynchronously.
 */
public interface AsyncStoppable extends Stoppable {
    /**
     * <p>Requests that this stoppable commence a graceful stop. Does not block. You should call {@link
     * Stoppable#stop} to wait for the stop process to complete.</p>
     *
     * <p>Generally, an {@code AsyncStoppable} should continue to complete existing work after this method has returned.
     * It should, however, stop accepting new work.</p>
     *
     * <p>
     * Requesting stopping does not guarantee the stoppable actually stops.
     * Requesting stopping means preparing for stopping; stopping accepting new work.
     * You have to call stop at some point anyway if your intention is to completely stop the stoppable.
     */
    void requestStop();
}