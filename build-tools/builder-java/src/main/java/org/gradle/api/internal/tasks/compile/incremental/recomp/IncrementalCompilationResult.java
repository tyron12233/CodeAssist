package org.gradle.api.internal.tasks.compile.incremental.recomp;

import org.gradle.api.tasks.WorkResult;

/**
 * A marker interface for incremental compilation result.
 */
public interface IncrementalCompilationResult extends WorkResult {
    WorkResult getCompilerResult();
    PreviousCompilationData getPreviousCompilationData();
    RecompilationSpec getRecompilationSpec();
}
