package com.tyron.builder.api.execution;

import static com.tyron.builder.api.work.AsyncWorkTracker.ProjectLockRetention.RELEASE_AND_REACQUIRE_PROJECT_LOCKS;
import static com.tyron.builder.api.work.AsyncWorkTracker.ProjectLockRetention.RELEASE_PROJECT_LOCKS;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.GeneratedSubclasses;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.UncheckedException;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.exceptions.DefaultMultiCauseException;
import com.tyron.builder.api.internal.exceptions.MultiCauseException;
import com.tyron.builder.api.internal.execution.ExecutionEngine;
import com.tyron.builder.api.internal.execution.ExecutionOutcome;
import com.tyron.builder.api.internal.execution.UnitOfWork;
import com.tyron.builder.api.internal.execution.WorkValidationContext;
import com.tyron.builder.api.internal.execution.WorkValidationException;
import com.tyron.builder.api.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.api.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.api.internal.execution.history.InputChangesInternal;
import com.tyron.builder.api.internal.execution.workspace.WorkspaceProvider;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.file.collections.LazilyInitializedFileCollection;
import com.tyron.builder.api.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.api.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.api.internal.operations.BuildOperationContext;
import com.tyron.builder.api.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.operations.BuildOperationRef;
import com.tyron.builder.api.internal.operations.RunnableBuildOperation;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.api.internal.snapshot.SnapshotUtil;
import com.tyron.builder.api.internal.snapshot.ValueSnapshot;
import com.tyron.builder.api.internal.tasks.InputChangesAwareTaskAction;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecuterResult;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskExecutionException;
import com.tyron.builder.api.internal.tasks.TaskExecutionOutcome;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.internal.tasks.properties.InputFilePropertySpec;
import com.tyron.builder.api.internal.tasks.properties.InputParameterUtils;
import com.tyron.builder.api.internal.tasks.properties.InputPropertySpec;
import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertySpec;
import com.tyron.builder.api.internal.tasks.properties.TaskProperties;
import com.tyron.builder.api.tasks.TaskOutputsInternal;
import com.tyron.builder.api.tasks.execution.ExecuteTaskActionBuildOperationType;
import com.tyron.builder.api.work.AsyncWorkTracker;
import com.tyron.builder.caching.internal.origin.OriginMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

/**
 * A {@link TaskExecuter} which executes the actions of a task.
 */
public class ExecuteActionsTaskExecuter implements TaskExecuter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ExecuteActionsTaskExecuter.class);

    private final BuildCacheState buildCacheState;
//    private final ScanPluginState scanPluginState;

    private final ExecutionHistoryStore executionHistoryStore;
    private final BuildOperationExecutor buildOperationExecutor;
    private final AsyncWorkTracker asyncWorkTracker;
//    private final org.gradle.api.execution.TaskActionListener actionListener;
//    private final TaskCacheabilityResolver taskCacheabilityResolver;
    private final ClassLoaderHierarchyHasher classLoaderHierarchyHasher;
    private final ExecutionEngine executionEngine;
    private final InputFingerprinter inputFingerprinter;
    private final ListenerManager listenerManager;
//    private final ReservedFileSystemLocationRegistry reservedFileSystemLocationRegistry;
    private final FileCollectionFactory fileCollectionFactory;
    private final FileOperations fileOperations;

    public ExecuteActionsTaskExecuter(
            BuildCacheState buildCacheState,
//            ScanPluginState scanPluginState,

            ExecutionHistoryStore executionHistoryStore,
            BuildOperationExecutor buildOperationExecutor,
            AsyncWorkTracker asyncWorkTracker,
//            org.gradle.api.execution.TaskActionListener actionListener,
//            TaskCacheabilityResolver taskCacheabilityResolver,
            ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
            ExecutionEngine executionEngine,
            InputFingerprinter inputFingerprinter,
            ListenerManager listenerManager,
//            ReservedFileSystemLocationRegistry reservedFileSystemLocationRegistry,
            FileCollectionFactory fileCollectionFactory,
            FileOperations fileOperations
    ) {
        this.buildCacheState = buildCacheState;
//        this.scanPluginState = scanPluginState;

        this.executionHistoryStore = executionHistoryStore;
        this.buildOperationExecutor = buildOperationExecutor;
        this.asyncWorkTracker = asyncWorkTracker;
//        this.actionListener = actionListener;
//        this.taskCacheabilityResolver = taskCacheabilityResolver;
        this.classLoaderHierarchyHasher = classLoaderHierarchyHasher;
        this.executionEngine = executionEngine;
        this.inputFingerprinter = inputFingerprinter;
        this.listenerManager = listenerManager;
//        this.reservedFileSystemLocationRegistry = reservedFileSystemLocationRegistry;
        this.fileCollectionFactory = fileCollectionFactory;
        this.fileOperations = fileOperations;
    }

    @Override
    public TaskExecuterResult execute(TaskInternal task,
                                      TaskStateInternal state,
                                      TaskExecutionContext context) {
        TaskExecution taskExecution = new TaskExecution(task, context, buildOperationExecutor, executionHistoryStore, inputFingerprinter, asyncWorkTracker);

        try {
            return executeIfValid(task, state, context, taskExecution);
        } catch (WorkValidationException e) {
            state.setOutcome(e);
            return TaskExecuterResult.WITHOUT_OUTPUTS;
        }
    }

    private TaskExecuterResult executeIfValid(TaskInternal task,
                                              TaskStateInternal state,
                                              TaskExecutionContext context,
                                              TaskExecution work) {
        ExecutionEngine.Request request = executionEngine.createRequest(work);
        request.withValidationContext(context.getValidationContext());
        ExecutionEngine.Result result = request.execute();
        result.getExecutionResult().ifSuccessfulOrElse(executionResult -> state
                        .setOutcome(TaskExecutionOutcome.valueOf(executionResult.getOutcome())),
                failure -> state.setOutcome(new TaskExecutionException(task, failure)));

        return new TaskExecuterResult() {
            @Override
            public Optional<OriginMetadata> getReusedOutputOriginMetadata() {
                return result.getReusedOutputOriginMetadata();
            }

            @Override
            public boolean executedIncrementally() {
                return result.getExecutionResult()
                        .map(executionResult -> executionResult.getOutcome() ==
                                                ExecutionOutcome.EXECUTED_INCREMENTALLY)
                        .getOrMapFailure(throwable -> false);
            }

            @Override
            public List<String> getExecutionReasons() {
                return result.getExecutionReasons();
            }

            @Override
            public Object getCachingState() {
                return result.getCachingState();
            }
        };
    }

    private static class PreviousOutputFileCollection extends LazilyInitializedFileCollection {
        private final TaskInternal task;
        private final FileCollectionFactory fileCollectionFactory;
        private final ImmutableSortedMap<String, FileSystemSnapshot> previousOutputs;

        public PreviousOutputFileCollection(TaskInternal task, FileCollectionFactory fileCollectionFactory, ImmutableSortedMap<String, FileSystemSnapshot> previousOutputs) {
            this.task = task;
            this.fileCollectionFactory = fileCollectionFactory;
            this.previousOutputs = previousOutputs;
        }

        @Override
        public FileCollectionInternal createDelegate() {
            List<File> outputs = previousOutputs.values().stream()
                    .map(SnapshotUtil::index)
                    .map(Map::keySet)
                    .flatMap(Collection::stream)
                    .map(File::new)
                    .collect(Collectors.toList());
            return fileCollectionFactory.fixed(outputs);
        }

        public String getDisplayName() {
            return "previous output files of " + task.toString();
        }
    }

    private class TaskExecution implements UnitOfWork {
        private final TaskInternal task;
        private final TaskExecutionContext context;
        private final BuildOperationExecutor buildOperationExecutor;
        private final ExecutionHistoryStore executionHistoryStore;
        private final InputFingerprinter inputFingerprinter;
        private AsyncWorkTracker asyncWorkTracker;

        private TaskExecution(TaskInternal task,
                              TaskExecutionContext context,
                              BuildOperationExecutor buildOperationExecutor,
                              ExecutionHistoryStore executionHistoryStore,
                              InputFingerprinter inputFingerprinter,
                              AsyncWorkTracker asyncWorkTracker) {
            this.task = task;
            this.context = context;
            this.buildOperationExecutor = buildOperationExecutor;
            this.executionHistoryStore = executionHistoryStore;
            this.inputFingerprinter = inputFingerprinter;
            this.asyncWorkTracker = asyncWorkTracker;
        }

        @Override
        public String getDisplayName() {
            return task.toString();
        }

        @Override
        public Identity identify(Map<String, ValueSnapshot> identityInputs,
                                 Map<String, CurrentFileCollectionFingerprint> identityFileInputs) {
            return task::getPath;
        }

        @Override
        public boolean isAllowedToLoadFromCache() {
            return context.getTaskExecutionMode().isAllowedToUseCachedResults();
        }

        @Override
        public Optional<Duration> getTimeout() {
            return Optional.ofNullable(null);
        }

        @Override
        public WorkOutput execute(ExecutionRequest executionRequest) {
            Optional<FileCollection> previousFilesOptional =
                    executionRequest.getPreviouslyProducedOutputs()
                            .map(previousOutputs -> new PreviousOutputFileCollection(this.task, fileCollectionFactory,
                                    previousOutputs));
            FileCollection previousFiles =
                    previousFilesOptional.orElseGet(fileCollectionFactory::empty);
            TaskOutputsInternal outputs = task.getOutputs();
            outputs.setPreviousOutputFiles(previousFiles);

            try {
                WorkResult workResult = executeWithPreviousOutputFiles(
                        ((InputChangesInternal) executionRequest.getInputChanges().orElse(null)));
                return new WorkOutput() {
                    @Override
                    public WorkResult getDidWork() {
                        return workResult;
                    }

                    @Override
                    public Object getOutput() {
                        throw new UnsupportedOperationException();
                    }
                };
            } finally {
                outputs.setPreviousOutputFiles(null);
            }
        }

        @Override
        public WorkspaceProvider getWorkspaceProvider() {
            return new WorkspaceProvider() {
                @Override
                public <T> T withWorkspace(String path, WorkspaceAction<T> action) {
                    return action.executeInWorkspace(null, context.getTaskExecutionMode()
                            .isTaskHistoryMaintained() ? executionHistoryStore : null);
                }
            };
        }

        @Override
        public InputFingerprinter getInputFingerprinter() {
            return inputFingerprinter;
        }

        private WorkResult executeWithPreviousOutputFiles(@Nullable InputChangesInternal inputChanges) {
            this.task.getState().setExecuting(true);

            try {
                LOGGER.debug("Executing actions for " + this.task);
                executeActions(this.task, inputChanges);
                return this.task.getState()
                        .getDidWork() ? WorkResult.DID_WORK : WorkResult.DID_NO_WORK;
            } finally {
                this.task.getState().setExecuting(false);
            }
        }


        @Override
        public void visitOutputs(File workspace, OutputVisitor visitor) {
            TaskProperties taskProperties = context.getTaskProperties();
            for (OutputFilePropertySpec property : taskProperties.getOutputFileProperties()) {
                File outputFile = property.getOutputFile();
                if (outputFile != null) {
                    visitor.visitOutputProperty(property.getPropertyName(), property.getOutputType(), outputFile, property.getPropertyFiles());
                }
            }
            for (File localStateRoot : taskProperties.getLocalStateFiles()) {
                visitor.visitLocalState(localStateRoot);
            }
            for (File destroyableRoot : taskProperties.getDestroyableFiles()) {
                visitor.visitDestroyable(destroyableRoot);
            }
        }

        @Override
        public void visitImplementations(ImplementationVisitor visitor) {
            visitor.visitImplementation(task.getClass());

            List<InputChangesAwareTaskAction> taskActions = task.getTaskActions();
            for (InputChangesAwareTaskAction taskAction : taskActions) {
                visitor.visitImplementation(taskAction.getActionImplementation(classLoaderHierarchyHasher));
            }
        }

        @Override
        public void visitRegularInputs(InputFingerprinter.InputVisitor visitor) {
            TaskProperties taskProperties = context.getTaskProperties();
            ImmutableSortedSet<InputPropertySpec> inputProperties = taskProperties.getInputProperties();
            ImmutableSortedSet<InputFilePropertySpec> inputFileProperties = taskProperties.getInputFileProperties();
            for (InputPropertySpec inputProperty : inputProperties) {
                visitor.visitInputProperty(inputProperty.getPropertyName(), () -> InputParameterUtils
                        .prepareInputParameterValue(inputProperty, task));
            }
            for (InputFilePropertySpec inputFileProperty : inputFileProperties) {
                Object value = inputFileProperty.getValue();
                // SkipWhenEmpty implies incremental.
                // If this file property is empty, then we clean up the previously generated outputs.
                // That means that there is a very close relation between the file property and the output.
                InputFingerprinter.InputPropertyType type = inputFileProperty.isSkipWhenEmpty()
                        ? InputFingerprinter.InputPropertyType.PRIMARY
                        : inputFileProperty.isIncremental()
                        ? InputFingerprinter.InputPropertyType.INCREMENTAL
                        : InputFingerprinter.InputPropertyType.NON_INCREMENTAL;
                String propertyName = inputFileProperty.getPropertyName();
                visitor.visitInputFileProperty(propertyName, type,
                        new InputFingerprinter.FileValueSupplier(
                                value,
                                inputFileProperty.getNormalizer(),
                                inputFileProperty.getDirectorySensitivity(),
                                inputFileProperty.getLineEndingNormalization(),
                                inputFileProperty::getPropertyFiles));
            }
        }

        private void executeActions(TaskInternal task, InputChangesInternal inputChanges) {
            boolean hasTaskListener = false;

            Iterator<Action<? super Task>> actions = task.getActions().iterator();
            while (actions.hasNext()) {
                InputChangesAwareTaskAction action = (InputChangesAwareTaskAction) actions.next();
                task.getState().setDidWork(true);
                task.getStandardOutputCapture().start();

                boolean hasMoreWork = hasTaskListener || actions.hasNext();
                try {
                    executeAction(action.getDisplayName(), task, action, inputChanges, hasMoreWork);
                } finally {
                    task.getStandardOutputCapture().stop();
                }
            }
        }

        private void executeAction(String actionDisplayName,
                                   TaskInternal task,
                                   InputChangesAwareTaskAction action,
                                   InputChangesInternal inputChanges,
                                   boolean hasMoreWork) {
            if (inputChanges != null) {
                action.setInputChanges(inputChanges);
            }

            buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) throws Exception {
                    try {
                        BuildOperationRef currentOperation = buildOperationExecutor.getCurrentOperation();
                        Throwable actionFailure = null;
                        try {
                            action.execute(task);
                        } catch (Throwable t) {
                            actionFailure = t;
                        } finally {
                            action.clearInputChanges();
                        }

                        try {
                            asyncWorkTracker.waitForCompletion(currentOperation, hasMoreWork ? RELEASE_AND_REACQUIRE_PROJECT_LOCKS : RELEASE_PROJECT_LOCKS);
                        } catch (Throwable t) {
                            List<Throwable> failures = Lists.newArrayList();

                            if (actionFailure != null) {
                                failures.add(actionFailure);
                            }

                            if (t instanceof MultiCauseException) {
                                failures.addAll(((MultiCauseException) t).getCauses());
                            } else {
                                failures.add(t);
                            }

                            if (failures.size() > 1) {
                                throw new MultipleTaskActionFailures("Multiple task action failures occurred:", failures);
                            } else {
                                throw UncheckedException.throwAsUncheckedException(failures.get(0));
                            }
                        }

                        if (actionFailure != null) {
                            context.failed(actionFailure);
                            throw UncheckedException.throwAsUncheckedException(actionFailure);
                        }
                    } finally {
                        context.setResult(ExecuteTaskActionBuildOperationType.RESULT_INSTANCE);
                    }
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor
                            .displayName(actionDisplayName + " for " + task.getPath())
                            .name(actionDisplayName)
                            .details(ExecuteTaskActionBuildOperationType.DETAILS_INSTANCE);
                }
            });

            Throwable actionFailure = null;

            try {
                action.execute(task);
            } catch (Throwable t) {
                actionFailure = t;
            } finally {
                action.clearInputChanges();
            }

            if (actionFailure != null) {
                throw UncheckedException.throwAsUncheckedException(actionFailure);
            }
        }

        @Override
        public void validate(WorkValidationContext validationContext) {
            Class<?> taskType = GeneratedSubclasses.unpackType(task);
            // TODO This should probably use the task class info store
//            boolean cacheable = taskType.isAnnotationPresent(CacheableTask.class);
            TypeValidationContext
                    typeValidationContext = validationContext.forType(taskType, true);
            context.getTaskProperties().validateType(typeValidationContext);
//            context.getTaskProperties().validate(new DefaultTaskValidationContext(
//                    fileOperations,
//                    reservedFileSystemLocationRegistry,
//                    typeValidationContext
//            ));
            context.getValidationAction().validate(context.getTaskExecutionMode().isTaskHistoryMaintained(), typeValidationContext);
        }

        @SuppressWarnings("deprecation")
        @Override
        public InputChangeTrackingStrategy getInputChangeTrackingStrategy() {
//            for (InputChangesAwareTaskAction taskAction : task.getTaskActions()) {
//                if (taskAction instanceof IncrementalInputsTaskAction) {
//                    return InputChangeTrackingStrategy.INCREMENTAL_PARAMETERS;
//                }
//                if (taskAction instanceof IncrementalTaskInputsTaskAction) {
//                    return InputChangeTrackingStrategy.ALL_PARAMETERS;
//                }
//            }
            return InputChangeTrackingStrategy.NONE;
        }
    }

    public static enum BuildCacheState {
        ENABLED,
        DISABLED;

        private BuildCacheState() {
        }
    }

    private static class MultipleTaskActionFailures extends DefaultMultiCauseException {
        public MultipleTaskActionFailures(String message, Iterable<? extends Throwable> causes) {
            super(message, causes);
        }
    }
}
