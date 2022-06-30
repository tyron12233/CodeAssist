package com.tyron.builder.api.internal.tasks;

import com.google.common.collect.Lists;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.FilePropertyContainer;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.file.CompositeFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.tasks.TaskInputPropertyBuilder;
import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.api.internal.tasks.properties.FileParameterUtils;
import com.tyron.builder.api.internal.tasks.properties.GetInputFilesVisitor;
import com.tyron.builder.api.internal.tasks.properties.GetInputPropertiesVisitor;
import com.tyron.builder.api.internal.tasks.properties.InputFilePropertyType;
import com.tyron.builder.api.internal.tasks.properties.InputParameterUtils;
import com.tyron.builder.api.internal.tasks.properties.InputPropertySpec;
import com.tyron.builder.api.internal.tasks.properties.PropertyValue;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.tasks.FileNormalizer;
import com.tyron.builder.api.tasks.TaskInputs;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class DefaultTaskInputs implements TaskInputsInternal {

    private final FileCollection allInputFiles;
    private final FileCollection allSourceFiles;
    private final TaskInternal task;
    private final TaskMutator taskMutator;
    private final PropertyWalker propertyWalker;
    private final FileCollectionFactory fileCollectionFactory;
    private final List<TaskInputPropertyRegistration> registeredProperties = Lists.newArrayList();
    private final FilePropertyContainer<TaskInputFilePropertyRegistration> registeredFileProperties = FilePropertyContainer.create();
    private final TaskInputs deprecatedThis;

    public DefaultTaskInputs(TaskInternal task, TaskMutator taskMutator, PropertyWalker propertyWalker, FileCollectionFactory fileCollectionFactory) {
        this.task = task;
        this.taskMutator = taskMutator;
        this.propertyWalker = propertyWalker;
        this.fileCollectionFactory = fileCollectionFactory;
        String taskDisplayName = task.toString();
        this.allInputFiles = new TaskInputUnionFileCollection(taskDisplayName, "input", false, task, propertyWalker, fileCollectionFactory);
        this.allSourceFiles = new TaskInputUnionFileCollection(taskDisplayName, "source", true, task, propertyWalker, fileCollectionFactory);
        this.deprecatedThis = new TaskInputsDeprecationSupport();
    }

    @Override
    public boolean getHasInputs() {
        HasInputsVisitor visitor = new HasInputsVisitor();
        TaskPropertyUtils.visitProperties(propertyWalker, task, visitor);
        return visitor.hasInputs();
    }

    @Override
    public void visitRegisteredProperties(PropertyVisitor visitor) {
        for (TaskInputFilePropertyRegistration registration : registeredFileProperties) {
            visitor.visitInputFileProperty(
                    registration.getPropertyName(),
                    registration.isOptional(),
                    registration.isSkipWhenEmpty(),
                    registration.getDirectorySensitivity(),
                    registration.getLineEndingNormalization(),
                    false,
                    registration.getNormalizer(),
                    registration.getValue(),
                    registration.getFilePropertyType()
            );
        }
        for (TaskInputPropertyRegistration registration : registeredProperties) {
            visitor.visitInputProperty(registration.getPropertyName(), registration.getValue(), registration.isOptional());
        }
    }

    @Override
    public FileCollection getFiles() {
        return allInputFiles;
    }

    @Override
    public TaskInputFilePropertyBuilderInternal files(final Object... paths) {
        return taskMutator.mutate("TaskInputs.files(Object...)", (Callable<TaskInputFilePropertyBuilderInternal>) () -> {
            StaticValue value = new StaticValue(unpackVarargs(paths));
            TaskInputFilePropertyRegistration registration = new DefaultTaskInputFilePropertyRegistration(value, InputFilePropertyType.FILES);
            registeredFileProperties.add(registration);
            return registration;
        });
    }

    private static Object unpackVarargs(Object[] args) {
        if (args.length == 1) {
            return args[0];
        }
        return args;
    }

    @Override
    public TaskInputFilePropertyBuilderInternal file(final Object path) {
        return taskMutator.mutate("TaskInputs.file(Object)", (Callable<TaskInputFilePropertyBuilderInternal>) () -> {
            StaticValue value = new StaticValue(path);
            TaskInputFilePropertyRegistration registration = new DefaultTaskInputFilePropertyRegistration(value, InputFilePropertyType.FILE);
            registeredFileProperties.add(registration);
            return registration;
        });
    }

    @Override
    public TaskInputFilePropertyBuilderInternal dir(final Object dirPath) {
        return taskMutator.mutate("TaskInputs.dir(Object)", (Callable<TaskInputFilePropertyBuilderInternal>) () -> {
            StaticValue value = new StaticValue(dirPath);
            TaskInputFilePropertyRegistration registration = new DefaultTaskInputFilePropertyRegistration(value, InputFilePropertyType.DIRECTORY);
            // Being an input directory implies ignoring of empty directories.
            registration.ignoreEmptyDirectories();
            registeredFileProperties.add(registration);
            return registration;
        });
    }

    @Override
    public boolean getHasSourceFiles() {
        GetInputFilesVisitor visitor = new GetInputFilesVisitor(task.toString(), fileCollectionFactory);
        TaskPropertyUtils.visitProperties(propertyWalker, task, visitor);
        return visitor.hasSourceFiles();
    }

    @Override
    public FileCollection getSourceFiles() {
        return allSourceFiles;
    }

    @Override
    public Map<String, Object> getProperties() {
        GetInputPropertiesVisitor visitor = new GetInputPropertiesVisitor();
        TaskPropertyUtils.visitProperties(propertyWalker, task, visitor);
        Map<String, Object> result = new HashMap<>();
        for (InputPropertySpec inputProperty : visitor.getProperties()) {
            result.put(inputProperty.getPropertyName(), InputParameterUtils.prepareInputParameterValue(inputProperty, task));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public TaskInputPropertyBuilder property(final String name, @Nullable final Object value) {
        return taskMutator.mutate("TaskInputs.property(String, Object)", (Callable<TaskInputPropertyBuilder>) () -> {
            StaticValue staticValue = new StaticValue(value);
            TaskInputPropertyRegistration registration = new DefaultTaskInputPropertyRegistration(name, staticValue);
            registeredProperties.add(registration);
            return registration;
        });
    }

    @Override
    public TaskInputs properties(final Map<String, ?> newProps) {
        taskMutator.mutate("TaskInputs.properties(Map)", () -> {
            for (Map.Entry<String, ?> entry : newProps.entrySet()) {
                StaticValue staticValue = new StaticValue(entry.getValue());
                String name = entry.getKey();
                registeredProperties.add(new DefaultTaskInputPropertyRegistration(name, staticValue));
            }
        });
        return deprecatedThis;
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        TaskPropertyUtils.visitProperties(propertyWalker, task, new PropertyVisitor.Adapter() {
            @Override
            public void visitInputProperty(String propertyName, PropertyValue value, boolean optional) {
                context.add(value.getTaskDependencies());
            }

            @Override
            public void visitInputFileProperty(
                    final String propertyName,
                    boolean optional,
                    boolean skipWhenEmpty,
                    DirectorySensitivity directorySensitivity,
                    LineEndingSensitivity lineEndingSensitivity,
                    boolean incremental,
                    @Nullable Class<? extends FileNormalizer> fileNormalizer,
                    PropertyValue value,
                    InputFilePropertyType filePropertyType
            ) {
                FileCollection actualValue = FileParameterUtils.resolveInputFileValue(fileCollectionFactory, filePropertyType, value);
                context.add(actualValue);
            }
        });
    }

    private static class TaskInputUnionFileCollection extends CompositeFileCollection implements Describable {
        private final boolean skipWhenEmptyOnly;
        private final String taskDisplayName;
        private final String type;
        private final TaskInternal task;
        private final PropertyWalker propertyWalker;
        private final FileCollectionFactory fileCollectionFactory;

        TaskInputUnionFileCollection(String taskDisplayName, String type, boolean skipWhenEmptyOnly, TaskInternal task, PropertyWalker propertyWalker, FileCollectionFactory fileCollectionFactory) {
            this.taskDisplayName = taskDisplayName;
            this.type = type;
            this.skipWhenEmptyOnly = skipWhenEmptyOnly;
            this.task = task;
            this.propertyWalker = propertyWalker;
            this.fileCollectionFactory = fileCollectionFactory;
        }

        @Override
        public String getDisplayName() {
            return taskDisplayName + " " + type + " files";
        }

        @Override
        protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
            TaskPropertyUtils.visitProperties(propertyWalker, task, new PropertyVisitor.Adapter() {
                @Override
                public void visitInputFileProperty(
                        final String propertyName,
                        boolean optional,
                        boolean skipWhenEmpty,
                        DirectorySensitivity directorySensitivity,
                        LineEndingSensitivity lineEndingSensitivity,
                        boolean incremental,
                        @Nullable Class<? extends FileNormalizer> fileNormalizer,
                        PropertyValue value, InputFilePropertyType filePropertyType
                ) {
                    if (!TaskInputUnionFileCollection.this.skipWhenEmptyOnly || skipWhenEmpty) {
                        FileCollectionInternal actualValue = FileParameterUtils
                                .resolveInputFileValue(fileCollectionFactory, filePropertyType, value);
                        visitor.accept(new PropertyFileCollection(task.toString(), propertyName, "input", actualValue));
                    }
                }
            });
        }
    }

    private static class HasInputsVisitor extends PropertyVisitor.Adapter {
        private boolean hasInputs;

        public boolean hasInputs() {
            return hasInputs;
        }

        @Override
        public void visitInputFileProperty(
                String propertyName,
                boolean optional,
                boolean skipWhenEmpty,
                DirectorySensitivity directorySensitivity,
                LineEndingSensitivity lineEndingSensitivity,
                boolean incremental,
                @Nullable Class<? extends FileNormalizer> fileNormalizer,
                PropertyValue value,
                InputFilePropertyType filePropertyType
        ) {
            hasInputs = true;
        }

        @Override
        public void visitInputProperty(String propertyName, PropertyValue value, boolean optional) {
            hasInputs = true;
        }
    }
}
