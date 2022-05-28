package com.tyron.builder.internal.remote.internal.hub;

/**
 * A listener which receives messages that cannot be delivered for some reason.
 */
public interface RejectedMessageListener {
    /**
     * Called when the given message cannot be delivered for some reason.
     */
    void messageDiscarded(Object message);
}
