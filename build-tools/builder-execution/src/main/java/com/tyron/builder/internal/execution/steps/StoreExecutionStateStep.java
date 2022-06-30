package com.tyron.builder.internal.execution.steps;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.history.OutputFileChanges;
import com.tyron.builder.internal.execution.history.changes.ChangeDetectorVisitor;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;

public class StoreExecutionStateStep<C extends PreviousExecutionContext, R extends AfterExecutionResult> implements Step<C, R> {

    private final Step<? super C, ? extends R> delegate;

    public StoreExecutionStateStep(
            Step<? super C, ? extends R> delegate
    ) {
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        R result = delegate.execute(work, context);
        context.getHistory()
                .ifPresent(history -> result.getAfterExecutionState()
                        .ifPresent(
                                afterExecutionState -> {
                                    // We do not store the history if there was a failure and the outputs did not change, since then the next execution can be incremental.
                                    // For example the current execution fails because of a compilation failure and for the next execution the source file is fixed,
                                    // so only the one changed source file needs to be compiled.
                                    // If there is no previous state, then we do have output changes
                                    boolean shouldStore = result.getExecutionResult().isSuccessful() || context.getPreviousExecutionState()
                                            .map(previewExecutionState -> didOutputsChange(
                                                    previewExecutionState.getOutputFilesProducedByWork(),
                                                    afterExecutionState.getOutputFilesProducedByWork()))
                                            .orElse(true);

                                    if (shouldStore) {
                                        history.store(
                                                context.getIdentity().getUniqueId(),
                                                result.getExecutionResult().isSuccessful(),
                                                afterExecutionState
                                        );
                                    }
                                }
                        )
                );
        return result;
    }

    private static boolean didOutputsChange(ImmutableSortedMap<String, FileSystemSnapshot> previous, ImmutableSortedMap<String, FileSystemSnapshot> current) {
        // If there are different output properties compared to the previous execution, then we do have output changes
        if (!previous.keySet().equals(current.keySet())) {
            return true;
        }

        // Otherwise, do deep compare of outputs
        ChangeDetectorVisitor visitor = new ChangeDetectorVisitor();
        OutputFileChanges changes = new OutputFileChanges(previous, current);
        changes.accept(visitor);
        return visitor.hasAnyChanges();
    }
}
