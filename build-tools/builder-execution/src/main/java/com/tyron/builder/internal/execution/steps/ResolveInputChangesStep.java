package com.tyron.builder.internal.execution.steps;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.WorkValidationContext;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.changes.InputChangesInternal;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.execution.history.changes.ExecutionStateChanges;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.work.InputChanges;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;

public class ResolveInputChangesStep<C extends IncrementalChangesContext, R extends Result> implements Step<C, R> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResolveInputChangesStep.class);

    private final Step<? super InputChangesContext, ? extends R> delegate;

    public ResolveInputChangesStep(Step<? super InputChangesContext, ? extends R> delegate) {
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        Optional<InputChangesInternal> inputChanges = determineInputChanges(work, context);
        return delegate.execute(work, new InputChangesContext() {
            @Override
            public Optional<InputChangesInternal> getInputChanges() {
                return inputChanges;
            }

            @Override
            public boolean isIncrementalExecution() {
                return inputChanges.map(InputChanges::isIncremental).orElse(false);
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
            public UnitOfWork.Identity getIdentity() {
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

            @Override
            public Optional<PreviousExecutionState> getPreviousExecutionState() {
                return context.getPreviousExecutionState();
            }

            @Override
            public Optional<ValidationResult> getValidationProblems() {
                return context.getValidationProblems();
            }

            @Override
            public Optional<BeforeExecutionState> getBeforeExecutionState() {
                return context.getBeforeExecutionState();
            }
        });
    }

    private static Optional<InputChangesInternal> determineInputChanges(UnitOfWork work, IncrementalChangesContext context) {
        if (!work.getInputChangeTrackingStrategy().requiresInputChanges()) {
            return Optional.empty();
        }
        ExecutionStateChanges changes = context.getChanges()
                .orElseThrow(() -> new IllegalStateException("Changes are not tracked, unable determine incremental changes."));
        InputChangesInternal inputChanges = changes.createInputChanges();
        if (!inputChanges.isIncremental()) {
            LOGGER.debug("The input changes require a full rebuild for incremental " + work.getDisplayName());
        }
        return Optional.of(inputChanges);
    }
}