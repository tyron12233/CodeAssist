package com.tyron.builder.process.internal.worker;

import com.tyron.builder.internal.logging.events.LogEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;
import com.tyron.builder.process.internal.worker.child.WorkerLoggingProtocol;

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
