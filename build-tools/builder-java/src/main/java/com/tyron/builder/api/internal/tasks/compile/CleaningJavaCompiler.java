package com.tyron.builder.api.internal.tasks.compile;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.api.internal.TaskOutputsInternal;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.WorkResults;
import com.tyron.builder.language.base.internal.compile.Compiler;
import com.tyron.builder.language.base.internal.tasks.StaleOutputCleaner;

import javax.annotation.Nullable;
import java.io.File;

/**
 * Deletes stale classes before invoking the actual compiler.
 */
public class CleaningJavaCompiler<T extends JavaCompileSpec> implements Compiler<T> {
    private final Compiler<T> compiler;
    private final TaskOutputsInternal taskOutputs;
    private final Deleter deleter;

    public CleaningJavaCompiler(Compiler<T> compiler, TaskOutputsInternal taskOutputs, Deleter deleter) {
        this.compiler = compiler;
        this.taskOutputs = taskOutputs;
        this.deleter = deleter;
    }

    @Override
    public WorkResult execute(T spec) {
        ImmutableSet.Builder<File> outputDirs = ImmutableSet.builderWithExpectedSize(3);
        MinimalJavaCompileOptions compileOptions = spec.getCompileOptions();
        addDirectoryIfNotNull(outputDirs, spec.getDestinationDir());
        addDirectoryIfNotNull(outputDirs, compileOptions.getAnnotationProcessorGeneratedSourcesDirectory());
        addDirectoryIfNotNull(outputDirs, compileOptions.getHeaderOutputDirectory());
        boolean cleanedOutputs = StaleOutputCleaner
                .cleanOutputs(deleter, taskOutputs.getPreviousOutputFiles(), outputDirs.build());

        Compiler<? super T> compiler = getCompiler();
        return compiler.execute(spec)
                .or(WorkResults.didWork(cleanedOutputs));
    }

    private void addDirectoryIfNotNull(ImmutableSet.Builder<File> outputDirs, @Nullable File dir) {
        if (dir != null) {
            outputDirs.add(dir);
        }
    }

    public Compiler<T> getCompiler() {
        return compiler;
    }
}
