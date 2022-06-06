package com.tyron.builder.internal.remote.internal.hub;

/**
 * A handler for messages that fail while streaming to the peer, either on the sending side or on the receiving side
 */
public interface StreamFailureHandler {
    /**
     * Called when notification of a streaming failure is received on an incoming channel.
     */
    void handleStreamFailure(Throwable t);
}
