package org.gradle.internal.logging.events;

import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.api.logging.LogLevel;

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

