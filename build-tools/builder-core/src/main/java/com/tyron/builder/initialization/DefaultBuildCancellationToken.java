package com.tyron.builder.initialization;

import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.internal.exceptions.DefaultMultiCauseException;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class DefaultBuildCancellationToken implements BuildCancellationToken {

    private final Object lock = new Object();
    private boolean cancelled;
    private List<Runnable> callbacks = new LinkedList<Runnable>();

    @Override
    public boolean isCancellationRequested() {
        synchronized (lock) {
            return cancelled;
        }
    }

    @Override
    public boolean addCallback(Runnable cancellationHandler) {
        boolean returnValue;
        synchronized (lock) {
            returnValue = cancelled;
            if (!cancelled) {
                callbacks.add(cancellationHandler);
            }
        }
        if (returnValue) {
            cancellationHandler.run();
        }
        return returnValue;
    }

    @Override
    public void removeCallback(Runnable cancellationHandler) {
        synchronized (lock) {
            callbacks.remove(cancellationHandler);
        }
    }

    @Override
    public void cancel() {
        List<Runnable> toCall = new ArrayList<Runnable>();
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toCall.addAll(callbacks);
            callbacks.clear();
        }

        List<Throwable> failures = new ArrayList<Throwable>();
        for (Runnable callback : toCall) {
            try {
                callback.run();
            } catch (Throwable ex) {
                failures.add(ex);
            }
        }
        if (!failures.isEmpty()) {
            throw new DefaultMultiCauseException("Failed to run cancellation actions.", failures);
        }
    }
}