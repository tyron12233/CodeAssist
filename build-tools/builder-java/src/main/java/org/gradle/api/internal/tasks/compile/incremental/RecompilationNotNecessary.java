package org.gradle.api.internal.tasks.compile.incremental;

import org.gradle.api.internal.tasks.compile.incremental.recomp.IncrementalCompilationResult;
import org.gradle.api.internal.tasks.compile.incremental.recomp.PreviousCompilationData;
import org.gradle.api.internal.tasks.compile.incremental.recomp.RecompilationSpec;
import org.gradle.api.tasks.WorkResult;

public class RecompilationNotNecessary implements WorkResult, IncrementalCompilationResult {

    private final PreviousCompilationData previousCompilationData;
    private final RecompilationSpec recompilationSpec;

    public RecompilationNotNecessary(PreviousCompilationData previousCompilationData, RecompilationSpec recompilationSpec) {
        this.previousCompilationData = previousCompilationData;
        this.recompilationSpec = recompilationSpec;
    }

    @Override
    public boolean getDidWork() {
        return false;
    }

    @Override
    public WorkResult getCompilerResult() {
        return this;
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