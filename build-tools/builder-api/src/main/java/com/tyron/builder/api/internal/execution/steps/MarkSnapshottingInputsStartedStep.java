package com.tyron.builder.api.internal.execution.steps;

import com.tyron.builder.api.internal.execution.UnitOfWork;

/**
 * This is a temporary measure for Gradle tasks to track a legacy measurement of all input snapshotting together.
 */
public class MarkSnapshottingInputsStartedStep<C extends Context, R extends Result> implements Step<C, R> {
    private final Step<? super C, ? extends R> delegate;

    public MarkSnapshottingInputsStartedStep(Step<? super C, ? extends R> delegate) {
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        work.markLegacySnapshottingInputsStarted();
        try {
            return delegate.execute(work, context);
        } finally {
            work.ensureLegacySnapshottingInputsClosed();
        }
    }
}
