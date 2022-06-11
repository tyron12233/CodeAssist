package com.tyron.builder.api.internal.tasks;

import com.google.common.collect.ImmutableSortedSet;
import groovy.lang.Closure;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.FilePropertyContainer;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.TaskOutputsInternal;
import com.tyron.builder.api.internal.file.CompositeFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.tasks.execution.SelfDescribingSpec;
import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertySpec;
import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertyType;
import com.tyron.builder.api.internal.tasks.properties.OutputFilesCollector;
import com.tyron.builder.api.internal.tasks.properties.OutputUnpacker;
import com.tyron.builder.api.internal.tasks.properties.PropertyValue;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.specs.AndSpec;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.api.tasks.TaskOutputFilePropertyBuilder;

import javax.annotation.Nullable;
import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@NonNullApi
public class DefaultTaskOutputs implements TaskOutputsInternal {
    private final FileCollection allOutputFiles;
    private final PropertyWalker propertyWalker;
    private final FileCollectionFactory fileCollectionFactory;
    private AndSpec<TaskInternal> upToDateSpec = AndSpec.empty();
    private final List<SelfDescribingSpec<TaskInternal>> cacheIfSpecs = new LinkedList<>();
    private final List<SelfDescribingSpec<TaskInternal>> doNotCacheIfSpecs = new LinkedList<>();
    private FileCollection previousOutputFiles;
    private final FilePropertyContainer<TaskOutputFilePropertyRegistration> registeredFileProperties = FilePropertyContainer.create();
    private final TaskInternal task;
    private final TaskMutator taskMutator;

    public DefaultTaskOutputs(final TaskInternal task, TaskMutator taskMutator, PropertyWalker propertyWalker, FileCollectionFactory fileCollectionFactory) {
        this.task = task;
        this.taskMutator = taskMutator;
        this.allOutputFiles = new TaskOutputUnionFileCollection(task);
        this.propertyWalker = propertyWalker;
        this.fileCollectionFactory = fileCollectionFactory;
    }

    @Override
    public void visitRegisteredProperties(PropertyVisitor visitor) {
        for (TaskOutputFilePropertyRegistration registration : registeredFileProperties) {
            visitor.visitOutputFileProperty(registration.getPropertyName(), registration.isOptional(), registration.getValue(), registration.getPropertyType());
        }
    }

    @Override
    public AndSpec<? super TaskInternal> getUpToDateSpec() {
        return upToDateSpec;
    }

    @Override
    public void upToDateWhen(final Closure upToDateClosure) {
        taskMutator.mutate("TaskOutputs.upToDateWhen(Closure)", () -> {
            upToDateSpec = upToDateSpec.and(upToDateClosure);
        });
    }

    @Override
    public void upToDateWhen(final Spec<? super Task> spec) {
        taskMutator.mutate("TaskOutputs.upToDateWhen(Spec)", () -> {
            upToDateSpec = upToDateSpec.and(spec);
        });
    }

    @Override
    public void cacheIf(final Spec<? super Task> spec) {
        cacheIf("Task outputs cacheable", spec);
    }

    @Override
    public void cacheIf(final String cachingEnabledReason, final Spec<? super Task> spec) {
        taskMutator.mutate("TaskOutputs.cacheIf(Spec)", () -> {
            cacheIfSpecs.add(new SelfDescribingSpec<>(spec, cachingEnabledReason));
        });
    }

    @Override
    public void doNotCacheIf(final String cachingDisabledReason, final Spec<? super Task> spec) {
        taskMutator.mutate("TaskOutputs.doNotCacheIf(Spec)", () -> {
            doNotCacheIfSpecs.add(new SelfDescribingSpec<>(spec, cachingDisabledReason));
        });
    }

    @Override
    public List<SelfDescribingSpec<TaskInternal>> getCacheIfSpecs() {
        return cacheIfSpecs;
    }

    @Override
    public List<SelfDescribingSpec<TaskInternal>> getDoNotCacheIfSpecs() {
        return doNotCacheIfSpecs;
    }

    @Override
    public boolean getHasOutput() {
        if (!upToDateSpec.isEmpty()) {
            return true;
        }
        HasDeclaredOutputsVisitor visitor = new HasDeclaredOutputsVisitor();
        TaskPropertyUtils.visitProperties(propertyWalker, task, visitor);
        return visitor.hasDeclaredOutputs();
    }

    @Override
    public FileCollection getFiles() {
        return allOutputFiles;
    }

    public ImmutableSortedSet<OutputFilePropertySpec> getFileProperties() {
        OutputFilesCollector collector = new OutputFilesCollector();
        TaskPropertyUtils.visitProperties(propertyWalker, task, new OutputUnpacker(task.toString(), fileCollectionFactory, false, false, collector));
        return collector.getFileProperties();
    }

    @Override
    public TaskOutputFilePropertyBuilder file(final Object path) {
        return taskMutator.mutate("TaskOutputs.file(Object)", (Callable<TaskOutputFilePropertyBuilder>) () -> {
            StaticValue value = new StaticValue(path);
            value.attachProducer(task);
            TaskOutputFilePropertyRegistration registration = new DefaultTaskOutputFilePropertyRegistration(value, OutputFilePropertyType.FILE);
            registeredFileProperties.add(registration);
            return registration;
        });
    }

    @Override
    public TaskOutputFilePropertyBuilder dir(final Object path) {
        return taskMutator.mutate("TaskOutputs.dir(Object)", (Callable<TaskOutputFilePropertyBuilder>) () -> {
            StaticValue value = new StaticValue(path);
            value.attachProducer(task);
            TaskOutputFilePropertyRegistration registration = new DefaultTaskOutputFilePropertyRegistration(value, OutputFilePropertyType.DIRECTORY);
            registeredFileProperties.add(registration);
            return registration;
        });
    }

    @Override
    public TaskOutputFilePropertyBuilder files(final @Nullable Object... paths) {
        return taskMutator.mutate("TaskOutputs.files(Object...)", (Callable<TaskOutputFilePropertyBuilder>) () -> {
            StaticValue value = new StaticValue(resolveSingleArray(paths));
            TaskOutputFilePropertyRegistration registration = new DefaultTaskOutputFilePropertyRegistration(value, OutputFilePropertyType.FILES);
            registeredFileProperties.add(registration);
            return registration;
        });
    }

    @Override
    public TaskOutputFilePropertyBuilder dirs(final Object... paths) {
        return taskMutator.mutate("TaskOutputs.dirs(Object...)", (Callable<TaskOutputFilePropertyBuilder>) () -> {
            StaticValue value = new StaticValue(resolveSingleArray(paths));
            TaskOutputFilePropertyRegistration registration = new DefaultTaskOutputFilePropertyRegistration(value, OutputFilePropertyType.DIRECTORIES);
            registeredFileProperties.add(registration);
            return registration;
        });
    }

    @Nullable
    private static Object resolveSingleArray(@Nullable Object[] paths) {
        return (paths != null && paths.length == 1) ? paths[0] : paths;
    }

    @Override
    public void setPreviousOutputFiles(FileCollection previousOutputFiles) {
        this.previousOutputFiles = previousOutputFiles;
    }

    @Override
    public Set<File> getPreviousOutputFiles() {
        if (previousOutputFiles == null) {
            throw new IllegalStateException("Task history is currently not available for this task.");
        }
        return previousOutputFiles.getFiles();
    }

    private static class HasDeclaredOutputsVisitor extends PropertyVisitor.Adapter {
        boolean hasDeclaredOutputs;

        @Override
        public void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType) {
            hasDeclaredOutputs = true;
        }

        public boolean hasDeclaredOutputs() {
            return hasDeclaredOutputs;
        }
    }

    private class TaskOutputUnionFileCollection extends CompositeFileCollection implements Describable {
        private final TaskInternal buildDependencies;

        public TaskOutputUnionFileCollection(TaskInternal buildDependencies) {
            this.buildDependencies = buildDependencies;
        }

        @Override
        public String getDisplayName() {
            return "task '" + task.getName() + "' output files";
        }

        @Override
        protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
            for (OutputFilePropertySpec propertySpec : getFileProperties()) {
                visitor.accept(propertySpec.getPropertyFiles());
            }
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            context.add(buildDependencies);
            super.visitDependencies(context);
        }
    }
}

