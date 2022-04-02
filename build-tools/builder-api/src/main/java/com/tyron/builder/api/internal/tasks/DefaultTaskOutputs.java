package com.tyron.builder.api.internal.tasks;

import com.google.common.collect.ImmutableSortedSet;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.FilePropertyContainer;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.file.CompositeFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertySpec;
import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertyType;
import com.tyron.builder.api.internal.tasks.properties.PropertyValue;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.tasks.TaskOutputFilePropertyBuilder;
import com.tyron.builder.api.tasks.TaskOutputsInternal;
import com.tyron.builder.api.tasks.TaskPropertyUtils;
import com.tyron.builder.api.util.Predicates;

import java.io.File;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nullable;

public class DefaultTaskOutputs implements TaskOutputsInternal {

    private final FileCollection allOutputFiles;
    private final PropertyWalker propertyWalker;
    private FileCollection previousOutputFiles;
    private Predicate<TaskInternal> upToDateSpec = Predicates.satisfyNone();
    private final FileCollectionFactory fileCollectionFactory;
    private final FilePropertyContainer<TaskOutputFilePropertyRegistration> registeredFileProperties = FilePropertyContainer.create();
    private final TaskInternal task;
    private final TaskMutator taskMutator;

    public DefaultTaskOutputs(TaskInternal task,
                              TaskMutator taskMutator,
                              PropertyWalker propertyWalker,
                              FileCollectionFactory fileCollectionFactory
    ) {
        this.task = task;
        this.propertyWalker = propertyWalker;
        this.fileCollectionFactory = fileCollectionFactory;
        this.allOutputFiles = new TaskOutputUnionCollection(task);
        this.taskMutator = taskMutator;
    }

    @Override
    public void upToDateWhen(Predicate<? super Task> upToDateSpec) {
        this.taskMutator.mutate("TaskOutputs.upToDateWhen(Spec)", () -> {
            this.upToDateSpec = this.upToDateSpec.and(upToDateSpec);
        });
    }

    @Override
    public void cacheIf(Predicate<? super Task> spec) {

    }

    @Override
    public void cacheIf(String cachingEnabledReason, Predicate<? super Task> spec) {

    }

    @Override
    public boolean getHasOutput() {
        if (!Objects.equals(upToDateSpec, Predicates.satisfyNone())) {
            return true;
        } else {
            DefaultTaskOutputs.HasDeclaredOutputsVisitor visitor = new DefaultTaskOutputs.HasDeclaredOutputsVisitor();
            TaskPropertyUtils.visitProperties(this.propertyWalker, this.task, visitor);
            return visitor.hasDeclaredOutputs();
        }
    }

    @Override
    public FileCollection getFiles() {
        return this.allOutputFiles;
    }

    public ImmutableSortedSet<OutputFilePropertySpec> getFileProperties() {
//        GetOutputFilesVisitor visitor = new GetOutputFilesVisitor(this.task.toString(), this.fileCollectionFactory, false);
//        TaskPropertyUtils.visitProperties(this.propertyWalker, this.task, visitor);
//        return visitor.getFileProperties();
        return ImmutableSortedSet.of();
    }

    @Override
    public TaskOutputFilePropertyBuilder file(Object path) {
        return this.taskMutator.mutate("TaskOutputs.file(Object)", () -> {
            StaticValue value = new StaticValue(path);
            value.attachProducer(this.task);
            TaskOutputFilePropertyRegistration registration = new DefaultTaskOutputFilePropertyRegistration(value, OutputFilePropertyType.FILE);
            this.registeredFileProperties.add(registration);
            return registration;
        });
    }

    @Override
    public TaskOutputFilePropertyBuilder files(Object... paths) {
        return taskMutator.mutate("TaskOutputs.dir(paths)", () -> {
            StaticValue value = new StaticValue(resolveSingleArray(paths));
            TaskOutputFilePropertyRegistration registration = new DefaultTaskOutputFilePropertyRegistration(value, OutputFilePropertyType.FILES);
            this.registeredFileProperties.add(registration);
            return registration;
        });
    }

    @Override
    public TaskOutputFilePropertyBuilder dir(Object path) {
        return taskMutator.mutate("TaskOutputs.dir(path)", () -> {
            StaticValue value = new StaticValue(path);
            value.attachProducer(this.task);
            TaskOutputFilePropertyRegistration registration = new DefaultTaskOutputFilePropertyRegistration(value, OutputFilePropertyType.DIRECTORY);
            this.registeredFileProperties.add(registration);
            return registration;
        });
    }

    @Override
    public TaskOutputFilePropertyBuilder dirs(Object... paths) {
        return this.taskMutator.mutate("TaskOutputs.dirs(Object...)", () -> {
            StaticValue value = new StaticValue(resolveSingleArray(paths));
            TaskOutputFilePropertyRegistration registration = new DefaultTaskOutputFilePropertyRegistration(value, OutputFilePropertyType.DIRECTORIES);
            this.registeredFileProperties.add(registration);
            return registration;
        });
    }

    @Override
    public void visitRegisteredProperties(PropertyVisitor visitor) {
        for (TaskOutputFilePropertyRegistration registration : registeredFileProperties) {
            visitor.visitOutputFileProperty(registration.getPropertyName(), registration.isOptional(), registration.getValue(), registration.getPropertyType());
        }
    }

    @Override
    public void setPreviousOutputFiles(FileCollection previousOutputFiles) {
        this.previousOutputFiles = previousOutputFiles;
    }

    @Override
    public Set<File> getPreviousOutputFiles() {
        if (this.previousOutputFiles == null) {
            throw new IllegalStateException("Task history is currently not available for this task.");
        } else {
            return this.previousOutputFiles.getFiles();
        }
    }

    @Nullable
    private static Object resolveSingleArray(@Nullable Object[] paths) {
        return paths != null && paths.length == 1 ? paths[0] : paths;
    }



    private class TaskOutputUnionCollection extends CompositeFileCollection implements Describable {
        private final TaskInternal buildDependencies;

        private TaskOutputUnionCollection(TaskInternal buildDependencies) {
            this.buildDependencies = buildDependencies;
        }

        public String getDisplayName() {
            return "task '" + DefaultTaskOutputs.this.task.getName() + "' output files";
        }

        @Override
        protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
            DefaultTaskOutputs.this.getFileProperties();
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            context.add(this.buildDependencies);
            super.visitDependencies(context);
        }
    }

    private static class HasDeclaredOutputsVisitor extends PropertyVisitor.Adapter {
        boolean hasDeclaredOutputs;

        private HasDeclaredOutputsVisitor() {
        }

        public void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType) {
            this.hasDeclaredOutputs = true;
        }

        public boolean hasDeclaredOutputs() {
            return this.hasDeclaredOutputs;
        }
    }
}
