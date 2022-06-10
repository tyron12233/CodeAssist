package org.gradle.internal.execution.steps.legacy;

import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.steps.CachingContext;
import org.gradle.internal.execution.steps.Result;
import org.gradle.internal.execution.steps.Step;

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