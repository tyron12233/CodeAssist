package com.tyron.builder.internal.dispatch;

public interface StreamCompletion {
    /**
     * Signals the end of the stream of messages.
     */
    void endStream();
}
