package com.tyron.builder.internal.execution.steps;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.timeout.Timeout;
import com.tyron.builder.internal.execution.timeout.TimeoutHandler;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;

import java.time.Duration;
import java.util.Optional;

public class TimeoutStep<C extends Context, R extends Result> implements Step<C, R> {

    private final TimeoutHandler timeoutHandler;
    private final CurrentBuildOperationRef currentBuildOperationRef;
    private final Step<? super C, ? extends R> delegate;

    public TimeoutStep(
            TimeoutHandler timeoutHandler,
            CurrentBuildOperationRef currentBuildOperationRef,
            Step<? super C, ? extends R> delegate
    ) {
        this.timeoutHandler = timeoutHandler;
        this.currentBuildOperationRef = currentBuildOperationRef;
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        Optional<Duration> timeoutProperty = work.getTimeout();
        if (timeoutProperty.isPresent()) {
            Duration timeout = timeoutProperty.get();
            if (timeout.isNegative()) {
                throw new InvalidUserDataException("Timeout of " + work.getDisplayName() + " must be positive, but was " + timeout.toString().substring(2));
            }
            return executeWithTimeout(work, context, timeout);
        } else {
            return executeWithoutTimeout(work, context);
        }
    }

    private R executeWithTimeout(UnitOfWork work, C context, Duration timeout) {
        Timeout taskTimeout = timeoutHandler.start(Thread.currentThread(), timeout, work, currentBuildOperationRef.get());
        try {
            return executeWithoutTimeout(work, context);
        } finally {
            if (taskTimeout.stop()) {
                //noinspection ResultOfMethodCallIgnored
                Thread.interrupted();
                //noinspection ThrowFromFinallyBlock
                throw new BuildException("Timeout has been exceeded");
            }
        }
    }

    private R executeWithoutTimeout(UnitOfWork work, C context) {
        return delegate.execute(work, context);
    }
}