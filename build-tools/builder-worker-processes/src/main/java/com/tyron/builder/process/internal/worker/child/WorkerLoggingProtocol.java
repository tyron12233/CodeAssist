package com.tyron.builder.process.internal.worker.child;

import com.tyron.builder.internal.logging.events.LogEvent;
import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;

public interface WorkerLoggingProtocol {
    void sendOutputEvent(LogEvent event);
    void sendOutputEvent(StyledTextOutputEvent event);
}
