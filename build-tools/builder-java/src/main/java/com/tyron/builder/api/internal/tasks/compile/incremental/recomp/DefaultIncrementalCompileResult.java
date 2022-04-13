package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.workers.internal.DefaultWorkResult;


/**
 * Marks compilation as beeing performed incrementally.
 */
public class DefaultIncrementalCompileResult extends DefaultWorkResult implements IncrementalCompilationResult {
    private final PreviousCompilationData previousCompilationData;
    private final RecompilationSpec recompilationSpec;
    private final WorkResult compilerResult;

    public DefaultIncrementalCompileResult(PreviousCompilationData previousCompilationData, RecompilationSpec recompilationSpec, WorkResult compilerResult) {
        super(compilerResult.getDidWork(), maybeException(compilerResult));
        this.previousCompilationData = previousCompilationData;
        this.recompilationSpec = recompilationSpec;
        this.compilerResult = compilerResult;
    }

    private static Throwable maybeException(WorkResult workResult) {
        if (workResult instanceof DefaultWorkResult) {
            return ((DefaultWorkResult) workResult).getException();
        }
        return null;
    }

    @Override
    public WorkResult getCompilerResult() {
        return compilerResult;
    }

    @Override
    public PreviousCompilationData getPreviousCompilationData() {
        return previousCompilationData;
    }

    @Override
    public RecompilationSpec getRecompilationSpec() {
        return recompilationSpec;
    }
}

