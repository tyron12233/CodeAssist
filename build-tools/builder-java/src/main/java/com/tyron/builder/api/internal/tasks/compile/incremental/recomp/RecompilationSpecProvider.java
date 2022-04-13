package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import com.tyron.builder.api.internal.tasks.compile.JavaCompileSpec;
import com.tyron.builder.api.tasks.WorkResult;

/**
 * In a typical incremental recompilation, there're three steps:
 * First, examine the incremental change files to get the classes to be recompiled: {@link #provideRecompilationSpec(CurrentCompilation, PreviousCompilation)}
 * Second, initialize the recompilation (e.g. delete stale class files and narrow down the source files to be recompiled): {@link #initializeCompilation(JavaCompileSpec, RecompilationSpec)}
 * Third, decorate the compilation result if necessary: {@link #decorateResult(RecompilationSpec, PreviousCompilationData, WorkResult)}, for example, notify whether current recompilation is full recompilation.
 */
public interface RecompilationSpecProvider {
    boolean isIncremental();

    RecompilationSpec provideRecompilationSpec(CurrentCompilation current, PreviousCompilation previous);

    boolean initializeCompilation(JavaCompileSpec spec, RecompilationSpec recompilationSpec);

    default WorkResult decorateResult(RecompilationSpec recompilationSpec, PreviousCompilationData previousCompilationData, WorkResult workResult) {
        if (!recompilationSpec.isFullRebuildNeeded()) {
            return new DefaultIncrementalCompileResult(previousCompilationData, recompilationSpec, workResult);
        }
        return workResult;
    }
}

