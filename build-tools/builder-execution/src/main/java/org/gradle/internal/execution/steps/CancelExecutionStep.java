package org.gradle.internal.execution.steps;

import org.gradle.api.BuildCancelledException;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.execution.UnitOfWork;

public class CancelExecutionStep<C extends Context, R extends Result> implements Step<C, R> {
    private final BuildCancellationToken cancellationToken;
    private final Step<? super C, ? extends R> delegate;

    public CancelExecutionStep(
            BuildCancellationToken cancellationToken,
            Step<? super C, ? extends R> delegate
    ) {
        this.cancellationToken = cancellationToken;
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        Thread thread = Thread.currentThread();
        Runnable interrupt = thread::interrupt;
        try {
            cancellationToken.addCallback(interrupt);
            return delegate.execute(work, context);
        } finally {
            cancellationToken.removeCallback(interrupt);
            if (cancellationToken.isCancellationRequested()) {
                Thread.interrupted();
                throw new BuildCancelledException("Build cancelled while executing " + work.getDisplayName());
            }
        }
    }
}