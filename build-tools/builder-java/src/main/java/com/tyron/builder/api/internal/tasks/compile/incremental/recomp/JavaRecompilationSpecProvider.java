package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.work.FileChange;

import java.util.Collections;
import java.util.Set;

public class JavaRecompilationSpecProvider extends AbstractRecompilationSpecProvider {

    public JavaRecompilationSpecProvider(
            Deleter deleter,
            FileOperations fileOperations,
            FileTree sourceTree,
            boolean incremental,
            Iterable<FileChange> sourceFileChanges
    ) {
        super(deleter, fileOperations, sourceTree, sourceFileChanges, incremental);
    }

    @Override
    protected Set<String> getFileExtensions() {
        return Collections.singleton(".java");
    }

    @Override
    protected boolean isIncrementalOnResourceChanges(CurrentCompilation currentCompilation) {
        return currentCompilation.getAnnotationProcessorPath().isEmpty();
    }
}

