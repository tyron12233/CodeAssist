package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import com.tyron.builder.api.tasks.WorkResult;

/**
 * A marker interface for incremental compilation result.
 */
public interface IncrementalCompilationResult extends WorkResult {
    WorkResult getCompilerResult();
    PreviousCompilationData getPreviousCompilationData();
    RecompilationSpec getRecompilationSpec();
}
