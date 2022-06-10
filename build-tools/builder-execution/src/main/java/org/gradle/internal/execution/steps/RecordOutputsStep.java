package org.gradle.internal.execution.steps;

import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.history.OutputFilesRepository;

public class RecordOutputsStep<C extends Context, R extends AfterExecutionResult> implements Step<C, R> {
    private final OutputFilesRepository outputFilesRepository;
    private final Step<? super C, ? extends R> delegate;

    public RecordOutputsStep(
            OutputFilesRepository outputFilesRepository,
            Step<? super C, ? extends R> delegate
    ) {
        this.outputFilesRepository = outputFilesRepository;
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        R result = delegate.execute(work, context);
        result.getAfterExecutionState()
                .ifPresent(afterExecutionState -> outputFilesRepository.recordOutputs(afterExecutionState.getOutputFilesProducedByWork().values()));
        return result;
    }
}