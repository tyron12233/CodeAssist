package com.tyron.builder.process.internal.worker.request;

import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.dispatch.StreamCompletion;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.remote.internal.hub.StreamFailureHandler;
import com.tyron.builder.process.internal.worker.DefaultWorkerLoggingProtocol;
import com.tyron.builder.process.internal.worker.WorkerProcessException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class Receiver extends DefaultWorkerLoggingProtocol implements ResponseProtocol, StreamCompletion, StreamFailureHandler {
    private static final Object NULL = new Object();
    private static final Object END = new Object();
    private final BlockingQueue<Object> received = new ArrayBlockingQueue<Object>(10);
    private final String baseName;
    private Object next;

    public Receiver(String baseName, OutputEventListener outputEventListener) {
        super(outputEventListener);
        this.baseName = baseName;
    }

    public boolean awaitNextResult() {
        try {
            if (next == null) {
                next = received.take();
            }
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
        return next != END;
    }

    public Object getNextResult() {
        awaitNextResult();
        Object next = this.next;
        if (next == END) {
            throw new IllegalStateException("No response received.");
        }
        this.next = null;
        if (next instanceof Failure) {
            Failure failure = (Failure) next;
            throw UncheckedException.throwAsUncheckedException(failure.failure);
        }
        return next == NULL ? null : next;
    }

    @Override
    public void handleStreamFailure(Throwable t) {
        failed(t);
    }

    @Override
    public void endStream() {
        try {
            received.put(END);
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public void completed(Object result) {
        try {
            received.put(result == null ? NULL : result);
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public void infrastructureFailed(Throwable failure) {
        failed(WorkerProcessException.runFailed(baseName, failure));
    }

    @Override
    public void failed(Throwable failure) {
        try {
            received.put(new Failure(failure));
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    static class Failure {
        final Throwable failure;

        public Failure(Throwable failure) {
            this.failure = failure;
        }
    }
}
