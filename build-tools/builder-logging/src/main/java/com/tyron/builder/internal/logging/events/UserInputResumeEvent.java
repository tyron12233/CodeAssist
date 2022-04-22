package com.tyron.builder.internal.logging.events;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.api.logging.LogLevel;

public class UserInputResumeEvent extends OutputEvent {

    @Override
    public LogLevel getLogLevel() {
        return LogLevel.QUIET;
    }
}
