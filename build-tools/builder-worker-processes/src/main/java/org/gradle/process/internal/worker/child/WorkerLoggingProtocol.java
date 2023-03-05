package org.gradle.process.internal.worker.child;

import org.gradle.internal.logging.events.LogEvent;
import org.gradle.internal.logging.events.StyledTextOutputEvent;

public interface WorkerLoggingProtocol {
    void sendOutputEvent(LogEvent event);
    void sendOutputEvent(StyledTextOutputEvent event);
}
