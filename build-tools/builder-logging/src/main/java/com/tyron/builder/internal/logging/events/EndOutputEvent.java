package com.tyron.builder.internal.logging.events;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.api.logging.LogLevel;

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
