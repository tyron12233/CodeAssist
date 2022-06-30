package com.tyron.builder.api.internal.tasks;

import com.google.common.collect.Lists;
import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;

import java.util.Collections;
import java.util.List;

@NonNullApi
public class DefaultTaskLocalState implements TaskLocalStateInternal {
    private final TaskMutator taskMutator;
    private final FileCollectionFactory fileCollectionFactory;
    private final List<Object> registeredPaths = Lists.newArrayList();

    public DefaultTaskLocalState(TaskMutator taskMutator, FileCollectionFactory fileCollectionFactory) {
        this.taskMutator = taskMutator;
        this.fileCollectionFactory = fileCollectionFactory;
    }

    @Override
    public void register(final Object... paths) {
        taskMutator.mutate("TaskLocalState.register(Object...)", () -> {
            Collections.addAll(DefaultTaskLocalState.this.registeredPaths, paths);
        });
    }

    @Override
    public void visitRegisteredProperties(PropertyVisitor visitor) {
        for (Object registeredPath : registeredPaths) {
            visitor.visitLocalStateProperty(registeredPath);
        }
    }

    @Override
    public FileCollection getRegisteredFiles() {
        return fileCollectionFactory.resolving("localState", registeredPaths);
    }
}
