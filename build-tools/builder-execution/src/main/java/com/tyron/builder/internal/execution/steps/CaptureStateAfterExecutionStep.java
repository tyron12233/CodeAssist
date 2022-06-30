package com.tyron.builder.internal.execution.steps;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.caching.internal.origin.OriginMetadata;
import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.execution.ExecutionResult;
import com.tyron.builder.internal.execution.OutputSnapshotter;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.history.AfterExecutionState;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.execution.history.impl.DefaultAfterExecutionState;
import com.tyron.builder.internal.id.UniqueId;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationType;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.time.Timer;

import java.time.Duration;
import java.util.Optional;

import static com.tyron.builder.internal.execution.history.impl.OutputSnapshotUtil.filterOutputsAfterExecution;

public class CaptureStateAfterExecutionStep<C extends BeforeExecutionContext> extends BuildOperationStep<C, AfterExecutionResult> {
    private final UniqueId buildInvocationScopeId;
    private final OutputSnapshotter outputSnapshotter;
    private final Step<? super C, ? extends Result> delegate;

    public CaptureStateAfterExecutionStep(
            BuildOperationExecutor buildOperationExecutor,
            UniqueId buildInvocationScopeId,
            OutputSnapshotter outputSnapshotter,
            Step<? super C, ? extends Result> delegate
    ) {
        super(buildOperationExecutor);
        this.buildInvocationScopeId = buildInvocationScopeId;
        this.outputSnapshotter = outputSnapshotter;
        this.delegate = delegate;
    }

    @Override
    public AfterExecutionResult execute(UnitOfWork work, C context) {
        Result result = delegate.execute(work, context);
        final Duration duration = result.getDuration();
        Optional<AfterExecutionState> afterExecutionState = context.getBeforeExecutionState()
                .flatMap(beforeExecutionState -> captureStateAfterExecution(work, context, beforeExecutionState, duration));

        return new AfterExecutionResult() {
            @Override
            public Optional<AfterExecutionState> getAfterExecutionState() {
                return afterExecutionState;
            }

            @Override
            public Try<ExecutionResult> getExecutionResult() {
                return result.getExecutionResult();
            }

            @Override
            public Duration getDuration() {
                return duration;
            }
        };
    }

    private Optional<AfterExecutionState> captureStateAfterExecution(UnitOfWork work, BeforeExecutionContext context, BeforeExecutionState beforeExecutionState, Duration duration) {
        return operation(
                operationContext -> {
                    try {
                        Timer timer = Time.startTimer();
                        ImmutableSortedMap<String, FileSystemSnapshot> outputsProducedByWork = captureOutputs(work, context, beforeExecutionState);
                        long snapshotOutputDuration = timer.getElapsedMillis();

                        // The origin execution time is recorded as “work duration” + “output snapshotting duration”,
                        // As this is _roughly_ the amount of time that is avoided by reusing the outputs,
                        // which is currently the _only_ thing this value is used for.
                        Duration originExecutionTime = duration.plus(Duration.ofMillis(snapshotOutputDuration));
                        OriginMetadata originMetadata = new OriginMetadata(buildInvocationScopeId.asString(), originExecutionTime);
                        AfterExecutionState afterExecutionState = new DefaultAfterExecutionState(beforeExecutionState, outputsProducedByWork, originMetadata, false);
                        operationContext.setResult(Operation.Result.INSTANCE);
                        return Optional.of(afterExecutionState);
                    } catch (OutputSnapshotter.OutputFileSnapshottingException e) {
                        work.handleUnreadableOutputs(e);
                        operationContext.setResult(Operation.Result.INSTANCE);
                        return Optional.empty();
                    }
                },
                BuildOperationDescriptor
                        .displayName("Snapshot outputs after executing " + work.getDisplayName())
                        .details(Operation.Details.INSTANCE)
        );
    }

    private ImmutableSortedMap<String, FileSystemSnapshot> captureOutputs(UnitOfWork work, BeforeExecutionContext context, BeforeExecutionState beforeExecutionState) {
        ImmutableSortedMap<String, FileSystemSnapshot> unfilteredOutputSnapshotsAfterExecution = outputSnapshotter.snapshotOutputs(work, context.getWorkspace());

        if (beforeExecutionState.getDetectedOverlappingOutputs().isPresent()) {
            ImmutableSortedMap<String, FileSystemSnapshot> previousExecutionOutputSnapshots = context.getPreviousExecutionState()
                    .map(PreviousExecutionState::getOutputFilesProducedByWork)
                    .orElse(ImmutableSortedMap.of());

            ImmutableSortedMap<String, FileSystemSnapshot> unfilteredOutputSnapshotsBeforeExecution = context.getBeforeExecutionState()
                    .map(BeforeExecutionState::getOutputFileLocationSnapshots)
                    .orElse(ImmutableSortedMap.of());

            return filterOutputsAfterExecution(previousExecutionOutputSnapshots, unfilteredOutputSnapshotsBeforeExecution, unfilteredOutputSnapshotsAfterExecution);
        } else {
            return unfilteredOutputSnapshotsAfterExecution;
        }
    }

    /*
     * This operation is only used here temporarily. Should be replaced with a more stable operation in the long term.
     */
    public interface Operation extends BuildOperationType<Operation.Details, Operation.Result> {
        interface Details {
            Details INSTANCE = new Details() {
            };
        }

        interface Result {
            Result INSTANCE = new Result() {
            };
        }
    }
}