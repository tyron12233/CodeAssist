package com.tyron.builder.api.internal.tasks;

import com.google.common.collect.Lists;
import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;

import java.util.Collections;
import java.util.List;

@NonNullApi
public class DefaultTaskDestroyables implements TaskDestroyablesInternal {
    private final TaskMutator taskMutator;
    private final FileCollectionFactory fileCollectionFactory;
    private final List<Object> registeredPaths = Lists.newArrayList();

    public DefaultTaskDestroyables(TaskMutator taskMutator, FileCollectionFactory fileCollectionFactory) {
        this.taskMutator = taskMutator;
        this.fileCollectionFactory = fileCollectionFactory;
    }

    @Override
    public void register(final Object... paths) {
        taskMutator.mutate("TaskDestroys.register(Object...)", () -> {
            Collections.addAll(DefaultTaskDestroyables.this.registeredPaths, paths);
        });
    }

    @Override
    public void visitRegisteredProperties(PropertyVisitor visitor) {
        for (Object registeredPath : registeredPaths) {
            visitor.visitDestroyableProperty(registeredPath);
        }
    }

    @Override
    public FileCollection getRegisteredFiles() {
        return fileCollectionFactory.resolving("destroyables", registeredPaths);
    }
}
