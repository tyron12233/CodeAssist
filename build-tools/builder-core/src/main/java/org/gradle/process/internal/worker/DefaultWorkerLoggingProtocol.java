package org.gradle.process.internal.worker;

import org.gradle.internal.logging.events.LogEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.events.StyledTextOutputEvent;
import org.gradle.process.internal.worker.child.WorkerLoggingProtocol;

public class DefaultWorkerLoggingProtocol implements WorkerLoggingProtocol {
    private OutputEventListener outputEventListener;

    public DefaultWorkerLoggingProtocol(OutputEventListener outputEventListener) {
        this.outputEventListener = outputEventListener;
    }

    @Override
    public void sendOutputEvent(LogEvent event) {
        outputEventListener.onOutput(event);
    }

    @Override
    public void sendOutputEvent(StyledTextOutputEvent event) {
        outputEventListener.onOutput(event);
    }

}
