package com.tyron.builder.internal.execution.steps;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.execution.ExecutionOutcome;
import com.tyron.builder.internal.execution.ExecutionResult;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.history.changes.InputChangesInternal;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationType;
import com.tyron.builder.internal.operations.CallableBuildOperation;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.time.Timer;
import com.tyron.builder.work.InputChanges;

import java.io.File;
import java.time.Duration;
import java.util.Optional;

public class ExecuteStep<C extends InputChangesContext> implements Step<C, Result> {

    private final BuildOperationExecutor buildOperationExecutor;

    public ExecuteStep(BuildOperationExecutor buildOperationExecutor) {
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public Result execute(UnitOfWork work, C context) {
        return buildOperationExecutor.call(new CallableBuildOperation<Result>() {
            @Override
            public Result call(BuildOperationContext operationContext) {
                Result result = executeInternal(work, context);
                operationContext.setResult(Operation.Result.INSTANCE);
                return result;
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor
                        .displayName("Executing " + work.getDisplayName())
                        .details(Operation.Details.INSTANCE);
            }
        });
    }

    private static Result executeInternal(UnitOfWork work, InputChangesContext context) {
        UnitOfWork.ExecutionRequest executionRequest = new UnitOfWork.ExecutionRequest() {
            @Override
            public File getWorkspace() {
                return context.getWorkspace();
            }

            @Override
            public Optional<InputChangesInternal> getInputChanges() {
                return context.getInputChanges();
            }

            @Override
            public Optional<ImmutableSortedMap<String, FileSystemSnapshot>> getPreviouslyProducedOutputs() {
                return context.getPreviousExecutionState()
                        .map(PreviousExecutionState::getOutputFilesProducedByWork);
            }
        };
        UnitOfWork.WorkOutput workOutput;

        Timer timer = Time.startTimer();
        try {
            workOutput = work.execute(executionRequest);
        } catch (Throwable t) {
            return ResultImpl.failed(t, Duration.ofMillis(timer.getElapsedMillis()));
        }

        Duration duration = Duration.ofMillis(timer.getElapsedMillis());
        ExecutionOutcome outcome = determineOutcome(context, workOutput);

        return ResultImpl.success(duration, new ExecutionResultImpl(outcome, workOutput));
    }

    private static ExecutionOutcome determineOutcome(InputChangesContext context, UnitOfWork.WorkOutput workOutput) {
        ExecutionOutcome outcome;
        switch (workOutput.getDidWork()) {
            case DID_NO_WORK:
                outcome = ExecutionOutcome.UP_TO_DATE;
                break;
            case DID_WORK:
                boolean incremental = context.getInputChanges()
                        .map(InputChanges::isIncremental)
                        .orElse(false);
                outcome = incremental
                        ? ExecutionOutcome.EXECUTED_INCREMENTALLY
                        : ExecutionOutcome.EXECUTED_NON_INCREMENTALLY;
                break;
            default:
                throw new AssertionError();
        }
        return outcome;
    }

    /*
     * This operation is only used here temporarily. Should be replaced with a more stable operation in the long term.
     */
    public interface Operation extends BuildOperationType<Operation.Details, Operation.Result> {
        interface Details {
            Operation.Details INSTANCE = new Operation.Details() {
            };
        }

        interface Result {
            Operation.Result INSTANCE = new Operation.Result() {
            };
        }
    }

    private static final class ResultImpl implements Result {

        private final Duration duration;
        private final Try<ExecutionResult> executionResultTry;

        private ResultImpl(Duration duration, Try<ExecutionResult> executionResultTry) {
            this.duration = duration;
            this.executionResultTry = executionResultTry;
        }

        private static Result failed(Throwable t, Duration duration) {
            return new ResultImpl(duration, Try.failure(t));
        }

        private static Result success(Duration duration, ExecutionResult executionResult) {
            return new ResultImpl(duration, Try.successful(executionResult));
        }

        @Override
        public Duration getDuration() {
            return duration;
        }

        @Override
        public Try<ExecutionResult> getExecutionResult() {
            return executionResultTry;
        }
    }

    private static final class ExecutionResultImpl implements ExecutionResult {
        private final ExecutionOutcome outcome;
        private final UnitOfWork.WorkOutput workOutput;

        public ExecutionResultImpl(ExecutionOutcome outcome, UnitOfWork.WorkOutput workOutput) {
            this.outcome = outcome;
            this.workOutput = workOutput;
        }

        @Override
        public ExecutionOutcome getOutcome() {
            return outcome;
        }

        @Override
        public Object getOutput() {
            return workOutput.getOutput();
        }
    }
}
