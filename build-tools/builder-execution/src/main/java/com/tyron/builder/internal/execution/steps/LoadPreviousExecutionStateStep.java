package com.tyron.builder.internal.execution.steps;


import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.UnitOfWork.Identity;
import com.tyron.builder.internal.execution.WorkValidationContext;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.snapshot.ValueSnapshot;

import java.io.File;
import java.util.Optional;

public class LoadPreviousExecutionStateStep<C extends WorkspaceContext, R extends Result> implements Step<C, R> {
    private final Step<? super PreviousExecutionContext, ? extends R> delegate;

    public LoadPreviousExecutionStateStep(Step<? super PreviousExecutionContext, ? extends R> delegate) {
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        Identity identity = context.getIdentity();
        Optional<PreviousExecutionState> previousExecutionState =
                context.getHistory().flatMap(history -> history.load(identity.getUniqueId()));
        return delegate.execute(work, new PreviousExecutionContext() {
            @Override
            public Optional<PreviousExecutionState> getPreviousExecutionState() {
                return previousExecutionState;
            }

            @Override
            public Optional<String> getNonIncrementalReason() {
                return context.getNonIncrementalReason();
            }

            @Override
            public WorkValidationContext getValidationContext() {
                return context.getValidationContext();
            }

            @Override
            public ImmutableSortedMap<String, ValueSnapshot> getInputProperties() {
                return context.getInputProperties();
            }

            @Override
            public ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileProperties() {
                return context.getInputFileProperties();
            }

            @Override
            public Identity getIdentity() {
                return context.getIdentity();
            }

            @Override
            public File getWorkspace() {
                return context.getWorkspace();
            }

            @Override
            public Optional<ExecutionHistoryStore> getHistory() {
                return context.getHistory();
            }
        });
    }
}
