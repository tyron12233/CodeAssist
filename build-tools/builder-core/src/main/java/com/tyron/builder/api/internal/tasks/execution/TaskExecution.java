package com.tyron.builder.api.internal.tasks.execution;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.tyron.builder.api.execution.TaskActionListener;
import com.tyron.builder.api.execution.TaskExecutionListener;
import com.tyron.builder.api.execution.internal.TaskInputsListeners;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.GeneratedSubclasses;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.TaskOutputsInternal;
import com.tyron.builder.api.internal.file.CompositeFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.file.collections.LazilyInitializedFileCollection;
import com.tyron.builder.api.internal.project.taskfactory.IncrementalInputsTaskAction;
import com.tyron.builder.api.internal.project.taskfactory.IncrementalTaskInputsTaskAction;
import com.tyron.builder.api.internal.tasks.DefaultTaskValidationContext;
import com.tyron.builder.api.internal.tasks.InputChangesAwareTaskAction;
import com.tyron.builder.api.internal.tasks.SnapshotTaskInputsBuildOperationResult;
import com.tyron.builder.api.internal.tasks.SnapshotTaskInputsBuildOperationType;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.properties.InputFilePropertySpec;
import com.tyron.builder.api.internal.tasks.properties.InputParameterUtils;
import com.tyron.builder.api.internal.tasks.properties.InputPropertySpec;
import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertySpec;
import com.tyron.builder.api.internal.tasks.properties.TaskProperties;
import com.tyron.builder.api.tasks.CacheableTask;
import com.tyron.builder.api.tasks.Copy;
import com.tyron.builder.api.tasks.StopActionException;
import com.tyron.builder.api.tasks.StopExecutionException;
import com.tyron.builder.api.tasks.Sync;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.deprecation.DeprecationLogger;
import com.tyron.builder.internal.deprecation.DeprecationMessageBuilder;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.internal.exceptions.DefaultMultiCauseException;
import com.tyron.builder.internal.exceptions.MultiCauseException;
import com.tyron.builder.internal.execution.OutputSnapshotter;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.WorkValidationContext;
import com.tyron.builder.internal.execution.caching.CachingDisabledReason;
import com.tyron.builder.internal.execution.caching.CachingState;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.OverlappingOutputs;
import com.tyron.builder.internal.execution.history.changes.InputChangesInternal;
import com.tyron.builder.internal.execution.workspace.WorkspaceProvider;
import com.tyron.builder.internal.file.ReservedFileSystemLocationRegistry;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationRef;
import com.tyron.builder.internal.operations.RunnableBuildOperation;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.SnapshotUtil;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.work.AsyncWorkTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.tyron.builder.internal.work.AsyncWorkTracker.ProjectLockRetention.RELEASE_AND_REACQUIRE_PROJECT_LOCKS;
import static com.tyron.builder.internal.work.AsyncWorkTracker.ProjectLockRetention.RELEASE_PROJECT_LOCKS;

public class TaskExecution implements UnitOfWork {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskExecution.class);
    private static final SnapshotTaskInputsBuildOperationType.Details SNAPSHOT_TASK_INPUTS_DETAILS = new SnapshotTaskInputsBuildOperationType.Details() {
    };

    private final TaskInternal task;
    private final TaskExecutionContext context;
    private final boolean emitLegacySnapshottingOperations;

    private final TaskActionListener actionListener;
    private final AsyncWorkTracker asyncWorkTracker;
    private final BuildOperationExecutor buildOperationExecutor;
    private final ClassLoaderHierarchyHasher classLoaderHierarchyHasher;
    private final ExecutionHistoryStore executionHistoryStore;
    private final FileCollectionFactory fileCollectionFactory;
    private final FileOperations fileOperations;
    private final InputFingerprinter inputFingerprinter;
    private final ListenerManager listenerManager;
    private final ReservedFileSystemLocationRegistry reservedFileSystemLocationRegistry;
    private final TaskCacheabilityResolver taskCacheabilityResolver;
    private final TaskInputsListeners taskInputsListeners;

    public TaskExecution(
            TaskInternal task,
            TaskExecutionContext context,
            boolean emitLegacySnapshottingOperations,

            TaskActionListener actionListener,
            AsyncWorkTracker asyncWorkTracker,
            BuildOperationExecutor buildOperationExecutor,
            ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
            ExecutionHistoryStore executionHistoryStore,
            FileCollectionFactory fileCollectionFactory,
            FileOperations fileOperations,
            InputFingerprinter inputFingerprinter,
            ListenerManager listenerManager,
            ReservedFileSystemLocationRegistry reservedFileSystemLocationRegistry,
            TaskCacheabilityResolver taskCacheabilityResolver,
            TaskInputsListeners taskInputsListeners
    ) {
        this.task = task;
        this.context = context;
        this.emitLegacySnapshottingOperations = emitLegacySnapshottingOperations;

        this.actionListener = actionListener;
        this.asyncWorkTracker = asyncWorkTracker;
        this.buildOperationExecutor = buildOperationExecutor;
        this.executionHistoryStore = executionHistoryStore;
        this.classLoaderHierarchyHasher = classLoaderHierarchyHasher;
        this.fileCollectionFactory = fileCollectionFactory;
        this.fileOperations = fileOperations;
        this.inputFingerprinter = inputFingerprinter;
        this.listenerManager = listenerManager;
        this.reservedFileSystemLocationRegistry = reservedFileSystemLocationRegistry;
        this.taskCacheabilityResolver = taskCacheabilityResolver;
        this.taskInputsListeners = taskInputsListeners;
    }

    @Override
    public Identity identify(Map<String, ValueSnapshot> identityInputs, Map<String, CurrentFileCollectionFingerprint> identityFileInputs) {
        return task::getPath;
    }

    @Override
    public WorkOutput execute(ExecutionRequest executionRequest) {
        FileCollection previousFiles = executionRequest.getPreviouslyProducedOutputs()
                .<FileCollection>map(previousOutputs -> new PreviousOutputFileCollection(task, fileCollectionFactory, previousOutputs))
                .orElseGet(fileCollectionFactory::empty);
        TaskOutputsInternal outputs = task.getOutputs();
        outputs.setPreviousOutputFiles(previousFiles);
        try {
            WorkResult didWork = executeWithPreviousOutputFiles(executionRequest.getInputChanges().orElse(null));
            return new WorkOutput() {
                @Override
                public WorkResult getDidWork() {
                    return didWork;
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

    private WorkResult executeWithPreviousOutputFiles(@Nullable InputChangesInternal inputChanges) {
        task.getState().setExecuting(true);
        try {
            LOGGER.debug("Executing actions for {}.", task);
            actionListener.beforeActions(task);
            executeActions(task, inputChanges);
            return task.getState().getDidWork() ? WorkResult.DID_WORK : WorkResult.DID_NO_WORK;
        } finally {
            task.getState().setExecuting(false);
            actionListener.afterActions(task);
        }
    }

    private void executeActions(TaskInternal task, @Nullable InputChangesInternal inputChanges) {
        boolean hasTaskListener = listenerManager.hasListeners(TaskActionListener.class) || listenerManager.hasListeners(TaskExecutionListener.class);
        Iterator<InputChangesAwareTaskAction> actions = new ArrayList<>(task.getTaskActions()).iterator();
        while (actions.hasNext()) {
            InputChangesAwareTaskAction action = actions.next();
            task.getState().setDidWork(true);
            task.getStandardOutputCapture().start();
            boolean hasMoreWork = hasTaskListener || actions.hasNext();
            try {
                executeAction(action.getDisplayName(), task, action, inputChanges, hasMoreWork);
            } catch (StopActionException e) {
                // Ignore
                LOGGER.debug("Action stopped by some action with message: {}", e.getMessage());
            } catch (StopExecutionException e) {
                LOGGER.info("Execution stopped by some action with message: {}", e.getMessage());
                break;
            } finally {
                task.getStandardOutputCapture().stop();
            }
        }
    }

    private void executeAction(String actionDisplayName, TaskInternal task, InputChangesAwareTaskAction action, @Nullable InputChangesInternal inputChanges, boolean hasMoreWork) {
        if (inputChanges != null) {
            action.setInputChanges(inputChanges);
        }
        buildOperationExecutor.run(new RunnableBuildOperation() {
            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor
                        .displayName(actionDisplayName + " for " + task.getIdentityPath().getPath())
                        .name(actionDisplayName)
                        .details(ExecuteTaskActionBuildOperationType.DETAILS_INSTANCE);
            }

            @Override
            public void run(BuildOperationContext context) {
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
        });
    }

    @Override
    public WorkspaceProvider getWorkspaceProvider() {
        return new WorkspaceProvider() {
            @Override
            public <T> T withWorkspace(String path, WorkspaceAction<T> action) {
                return action.executeInWorkspace(null, context.getTaskExecutionMode().isTaskHistoryMaintained()
                        ? executionHistoryStore
                        : null);
            }
        };
    }

    @Override
    public InputFingerprinter getInputFingerprinter() {
        return inputFingerprinter;
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
            visitor.visitInputProperty(inputProperty.getPropertyName(), () -> InputParameterUtils.prepareInputParameterValue(inputProperty, task));
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
    public void handleUnreadableInputs(InputFingerprinter.InputFileFingerprintingException ex) {
        nagUserAboutUnreadableInputsOrOutputs("input", ex.getPropertyName(), ex.getCause());
    }

    @Override
    public void handleUnreadableOutputs(OutputSnapshotter.OutputFileSnapshottingException ex) {
        nagUserAboutUnreadableInputsOrOutputs("output", ex.getPropertyName(), ex.getCause());
    }

    private void nagUserAboutUnreadableInputsOrOutputs(String propertyType, String propertyName, Throwable cause) {
        if (!(cause instanceof UncheckedIOException || cause instanceof com.tyron.builder.api.UncheckedIOException)) {
            throw UncheckedException.throwAsUncheckedException(cause);
        }
        LOGGER.info("Cannot access {} property '{}' of {}", propertyType, propertyName, getDisplayName(), cause);
        boolean isDestinationDir = propertyName.equals("destinationDir");
        DeprecationMessageBuilder<?> builder;
        if (isDestinationDir && task instanceof Copy) {
            builder = DeprecationLogger.deprecateAction("Cannot access a file in the destination directory (see --info log for details). Copying to a directory which contains unreadable content")
                    .withAdvice("Declare the task as untracked by using Task.doNotTrackState().");
        } else if (isDestinationDir && task instanceof Sync) {
            builder = DeprecationLogger.deprecateAction("Cannot access a file in the destination directory (see --info log for details). Syncing to a directory which contains unreadable content")
                    .withAdvice("Use a Copy task with Task.doNotTrackState() instead.");
        } else {
            builder = DeprecationLogger.deprecateAction(String.format("Cannot access %s property '%s' of %s (see --info log for details). Accessing unreadable inputs or outputs",
                    propertyType, propertyName, getDisplayName()))
                    .withAdvice("Declare the task as untracked by using Task.doNotTrackState().");

        }
        builder
                .willBecomeAnErrorInGradle8()
                .withUpgradeGuideSection(7, "declare_unreadable_input_output")
                .nagUser();
    }

    @Override
    public OverlappingOutputHandling getOverlappingOutputHandling() {
        return OverlappingOutputHandling.DETECT_OVERLAPS;
    }

    @Override
    public boolean shouldCleanupOutputsOnNonIncrementalExecution() {
        return getInputChangeTrackingStrategy() == InputChangeTrackingStrategy.INCREMENTAL_PARAMETERS;
    }

    @Override
    public Optional<CachingDisabledReason> shouldDisableCaching(@Nullable OverlappingOutputs detectedOverlappingOutputs) {
        if (task.isHasCustomActions()) {
            LOGGER.info("Custom actions are attached to {}.", task);
        }

        return taskCacheabilityResolver.shouldDisableCaching(
                task,
                context.getTaskProperties(),
                task.getOutputs().getCacheIfSpecs(),
                task.getOutputs().getDoNotCacheIfSpecs(),
                detectedOverlappingOutputs
        );
    }

    @Override
    public boolean isAllowedToLoadFromCache() {
        return context.getTaskExecutionMode().isAllowedToUseCachedResults();
    }

    @Override
    public Optional<Duration> getTimeout() {
        return Optional.ofNullable(task.getTimeout().getOrNull());
    }

    @SuppressWarnings("deprecation")
    @Override
    public InputChangeTrackingStrategy getInputChangeTrackingStrategy() {
        for (InputChangesAwareTaskAction taskAction : task.getTaskActions()) {
            if (taskAction instanceof IncrementalInputsTaskAction) {
                return InputChangeTrackingStrategy.INCREMENTAL_PARAMETERS;
            }
            if (taskAction instanceof IncrementalTaskInputsTaskAction) {
                return InputChangeTrackingStrategy.ALL_PARAMETERS;
            }
        }
        return InputChangeTrackingStrategy.NONE;
    }

    @Override
    public void markLegacySnapshottingInputsStarted() {
        // Note: this operation should be added only if the scan plugin is applied, but SnapshotTaskInputsOperationIntegrationTest
        //   expects it to be added also when the build cache is enabled (but not the scan plugin)
        if (emitLegacySnapshottingOperations) {
            BuildOperationContext operationContext = buildOperationExecutor.start(BuildOperationDescriptor
                    .displayName("Snapshot task inputs for " + task.getIdentityPath())
                    .name("Snapshot task inputs")
                    .details(SNAPSHOT_TASK_INPUTS_DETAILS));
            context.setSnapshotTaskInputsBuildOperationContext(operationContext);
        }
    }

    @Override
    public void markLegacySnapshottingInputsFinished(CachingState cachingState) {
        context.removeSnapshotTaskInputsBuildOperationContext()
                .ifPresent(operation -> operation.setResult(new SnapshotTaskInputsBuildOperationResult(cachingState, context.getTaskProperties().getInputFileProperties())));
    }

    @Override
    public void ensureLegacySnapshottingInputsClosed() {
        // If the operation hasn't finished normally (because of a shortcut or an error), we close it without a cache key
        context.removeSnapshotTaskInputsBuildOperationContext()
                .ifPresent(operation -> operation.setResult(new SnapshotTaskInputsBuildOperationResult(CachingState.NOT_DETERMINED, Collections.emptySet())));
    }

    @Override
    public void validate(WorkValidationContext validationContext) {
        Class<?> taskType = GeneratedSubclasses.unpackType(task);
        // TODO This should probably use the task class info store
        boolean cacheable = taskType.isAnnotationPresent(CacheableTask.class);
        TypeValidationContext typeValidationContext = validationContext.forType(taskType, cacheable);
        context.getTaskProperties().validateType(typeValidationContext);
        context.getTaskProperties().validate(new DefaultTaskValidationContext(
                fileOperations,
                reservedFileSystemLocationRegistry,
                typeValidationContext
        ));
        context.getValidationAction().validate(context.getTaskExecutionMode().isTaskHistoryMaintained(), typeValidationContext);
    }

    @Override
    public void broadcastRelevantFileSystemInputs(boolean hasEmptySources) {
        taskInputsListeners.broadcastFileSystemInputsOf(task, new CompositeFileCollection() {
            @Override
            public String getDisplayName() {
                return TaskExecution.this.getDisplayName() + " relevant file inputs";
            }

            @Override
            protected void visitChildren(Consumer<FileCollectionInternal> visitor) {
                for (InputFilePropertySpec filePropertySpec : context.getTaskProperties().getInputFileProperties()) {
                    if (!hasEmptySources || filePropertySpec.isSkipWhenEmpty()) {
                        visitor.accept(filePropertySpec.getPropertyFiles());
                    }
                }
            }
        });
    }

    @Override
    public String getDisplayName() {
        return task.toString();
    }

    @Override
    public String toString() {
        return getDisplayName();
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

        @Override
        public String getDisplayName() {
            return "previous output files of " + task;
        }
    }

    @Contextual
    private static class MultipleTaskActionFailures extends DefaultMultiCauseException {
        public MultipleTaskActionFailures(String message, Iterable<? extends Throwable> causes) {
            super(message, causes);
        }
    }
}
