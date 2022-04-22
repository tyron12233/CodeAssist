package com.tyron.builder.internal.execution.steps;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.execution.ExecutionOutcome;
import com.tyron.builder.internal.execution.ExecutionResult;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.history.AfterExecutionState;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.execution.history.impl.DefaultAfterExecutionState;
import com.tyron.builder.caching.internal.origin.OriginMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Formatter;
import java.util.List;
import java.util.Optional;

public class SkipUpToDateStep<C extends IncrementalChangesContext> implements Step<C, UpToDateResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkipUpToDateStep.class);

    private final Step<? super C, ? extends AfterExecutionResult> delegate;

    public SkipUpToDateStep(Step<? super C, ? extends AfterExecutionResult> delegate) {
        this.delegate = delegate;
    }

    @Override
    public UpToDateResult execute(UnitOfWork work, C context) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Determining if " + work.getDisplayName() + " is up-to-date");
        }
        ImmutableList<String> reasons = context.getRebuildReasons();
        return context.getChanges()
                .filter(__ -> reasons.isEmpty())
                .map(changes -> skipExecution(work, changes.getBeforeExecutionState(), context))
                .orElseGet(() -> executeBecause(work, reasons, context));
    }

    private UpToDateResult skipExecution(UnitOfWork work, BeforeExecutionState beforeExecutionState, C context) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Skipping " + work.getDisplayName() + "  as it is up-to-date.");
        }
        @SuppressWarnings("OptionalGetWithoutIsPresent") PreviousExecutionState
                previousExecutionState = context.getPreviousExecutionState().get();
        AfterExecutionState afterExecutionState = new DefaultAfterExecutionState(
                beforeExecutionState,
                previousExecutionState.getOutputFilesProducedByWork(),
                previousExecutionState.getOriginMetadata(),
                true);
        return new UpToDateResult() {
            @Override
            public ImmutableList<String> getExecutionReasons() {
                return ImmutableList.of();
            }

            @Override
            public Optional<AfterExecutionState> getAfterExecutionState() {
                return Optional.of(afterExecutionState);
            }

            @Override
            public Optional<OriginMetadata> getReusedOutputOriginMetadata() {
                return Optional.of(previousExecutionState.getOriginMetadata());
            }

            @Override
            public Try<ExecutionResult> getExecutionResult() {
                return Try.successful(new ExecutionResult() {
                    @Override
                    public ExecutionOutcome getOutcome() {
                        return ExecutionOutcome.UP_TO_DATE;
                    }

                    @Override
                    public Object getOutput() {
                        return work.loadRestoredOutput(context.getWorkspace());
                    }
                });
            }

            @Override
            public Duration getDuration() {
                return previousExecutionState.getOriginMetadata().getExecutionTime();
            }
        };
    }

    private UpToDateResult executeBecause(UnitOfWork work, ImmutableList<String> reasons, C context) {
        logExecutionReasons(reasons, work);
        AfterExecutionResult result = delegate.execute(work, context);
        return new UpToDateResult() {
            @Override
            public ImmutableList<String> getExecutionReasons() {
                return reasons;
            }

            @Override
            public Optional<AfterExecutionState> getAfterExecutionState() {
                return result.getAfterExecutionState();
            }

            @Override
            public Optional<OriginMetadata> getReusedOutputOriginMetadata() {
                return result.getAfterExecutionState()
                        .filter(AfterExecutionState::isReused)
                        .map(AfterExecutionState::getOriginMetadata);
            }

            @Override
            public Duration getDuration() {
                return result.getDuration();
            }

            @Override
            public Try<ExecutionResult> getExecutionResult() {
                return result.getExecutionResult();
            }
        };
    }

    private void logExecutionReasons(List<String> reasons, UnitOfWork work) {
        if (LOGGER.isInfoEnabled()) {
            Formatter formatter = new Formatter();
            formatter.format("%s is not up-to-date because:", Strings.nullToEmpty(work.getDisplayName()).toUpperCase());
            for (String message : reasons) {
                formatter.format("%n  %s", message);
            }
            LOGGER.info(formatter.toString());
        }
    }
}
