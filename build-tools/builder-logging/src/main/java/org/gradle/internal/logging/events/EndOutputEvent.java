package org.gradle.internal.logging.events;

import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.api.logging.LogLevel;

import javax.annotation.Nullable;

/**
 * Notifies output consumer tof the end of the output event stream.
 */
public class EndOutputEvent extends OutputEvent {
    @Nullable
    @Override
    public LogLevel getLogLevel() {
        return null;
    }
}
