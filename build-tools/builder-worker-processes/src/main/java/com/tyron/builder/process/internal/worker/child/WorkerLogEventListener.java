package com.tyron.builder.process.internal.worker.child;

import com.tyron.builder.internal.logging.events.LogEvent;
import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

public class WorkerLogEventListener implements OutputEventListener {
    private final AtomicReference<WorkerLoggingProtocol> workerLoggingProtocol;

    public WorkerLogEventListener() {
        this.workerLoggingProtocol = new AtomicReference<WorkerLoggingProtocol>();
    }

    public void setWorkerLoggingProtocol(WorkerLoggingProtocol workerLoggingProtocol) {
        this.workerLoggingProtocol.getAndSet(workerLoggingProtocol);
    }

    public Object withWorkerLoggingProtocol(WorkerLoggingProtocol newLoggingProtocol, Callable<?> callable) throws Exception {
        WorkerLoggingProtocol defaultProtocol = workerLoggingProtocol.getAndSet(newLoggingProtocol);
        try {
            return callable.call();
        } finally {
            workerLoggingProtocol.getAndSet(defaultProtocol);
        }
    }

    @Override
    public void onOutput(OutputEvent event) {
        WorkerLoggingProtocol loggingProtocol = workerLoggingProtocol.get();

        if (loggingProtocol == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " received an output event before the worker logging protocol object was set.");
        }

        if (event instanceof LogEvent) {
            loggingProtocol.sendOutputEvent((LogEvent) event);
        } else if (event instanceof StyledTextOutputEvent) {
            loggingProtocol.sendOutputEvent((StyledTextOutputEvent) event);
        }
    }
}
