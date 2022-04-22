package com.tyron.builder.internal.logging.events;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.api.logging.LogLevel;

import javax.annotation.Nullable;

/**
 * Notifies output consumers that might be queueing messages to immediately flush their queues.
 */
public class FlushOutputEvent extends OutputEvent {
    @Nullable
    @Override
    public LogLevel getLogLevel() {
        return null;
    }
}

