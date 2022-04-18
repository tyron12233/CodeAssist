package com.tyron.builder.internal.execution.steps.legacy;

import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.steps.CachingContext;
import com.tyron.builder.internal.execution.steps.Result;
import com.tyron.builder.internal.execution.steps.Step;

/**
 * This is a temporary measure for Gradle tasks to track a legacy measurement of all input snapshotting together.
 */
public class MarkSnapshottingInputsFinishedStep<C extends CachingContext, R extends Result> implements Step<C, R> {
    private final Step<? super C, ? extends R> delegate;

    public MarkSnapshottingInputsFinishedStep(Step<? super C, ? extends R> delegate) {
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        work.markLegacySnapshottingInputsFinished(context.getCachingState());
        return delegate.execute(work, context);
    }
}