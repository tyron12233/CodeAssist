package com.tyron.builder.api.internal.tasks.execution;


import com.tyron.builder.api.execution.TaskActionListener;
import com.tyron.builder.api.execution.internal.TaskInputsListeners;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecuterResult;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskExecutionOutcome;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.tasks.TaskExecutionException;
import com.tyron.builder.caching.internal.origin.OriginMetadata;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.ExecutionEngine;
import com.tyron.builder.internal.execution.ExecutionEngine.Result;
import com.tyron.builder.internal.execution.ExecutionOutcome;
import com.tyron.builder.internal.execution.WorkValidationException;
import com.tyron.builder.internal.execution.caching.CachingState;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.file.ReservedFileSystemLocationRegistry;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.work.AsyncWorkTracker;

import java.util.List;
import java.util.Optional;

/**
 * A {@link TaskExecuter} which executes the actions of a task.
 */
public class ExecuteActionsTaskExecuter implements TaskExecuter {
    public enum BuildCacheState {
        ENABLED, DISABLED
    }

    public enum ScanPluginState {
        APPLIED, NOT_APPLIED
    }

    private final BuildCacheState buildCacheState;
    private final ScanPluginState scanPluginState;

    private final ExecutionHistoryStore executionHistoryStore;
    private final BuildOperationExecutor buildOperationExecutor;
    private final AsyncWorkTracker asyncWorkTracker;
    private final TaskActionListener actionListener;
    private final TaskCacheabilityResolver taskCacheabilityResolver;
    private final ClassLoaderHierarchyHasher classLoaderHierarchyHasher;
    private final ExecutionEngine executionEngine;
    private final InputFingerprinter inputFingerprinter;
    private final ListenerManager listenerManager;
    private final ReservedFileSystemLocationRegistry reservedFileSystemLocationRegistry;
    private final FileCollectionFactory fileCollectionFactory;
    private final FileOperations fileOperations;
    private final TaskInputsListeners taskInputsListeners;

    public ExecuteActionsTaskExecuter(
            BuildCacheState buildCacheState,
            ScanPluginState scanPluginState,

            ExecutionHistoryStore executionHistoryStore,
            BuildOperationExecutor buildOperationExecutor,
            AsyncWorkTracker asyncWorkTracker,
            TaskActionListener actionListener,
            TaskCacheabilityResolver taskCacheabilityResolver,
            ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
            ExecutionEngine executionEngine,
            InputFingerprinter inputFingerprinter,
            ListenerManager listenerManager,
            ReservedFileSystemLocationRegistry reservedFileSystemLocationRegistry,
            FileCollectionFactory fileCollectionFactory,
            FileOperations fileOperations,
            TaskInputsListeners taskInputsListeners
    ) {
        this.buildCacheState = buildCacheState;
        this.scanPluginState = scanPluginState;

        this.executionHistoryStore = executionHistoryStore;
        this.buildOperationExecutor = buildOperationExecutor;
        this.asyncWorkTracker = asyncWorkTracker;
        this.actionListener = actionListener;
        this.taskCacheabilityResolver = taskCacheabilityResolver;
        this.classLoaderHierarchyHasher = classLoaderHierarchyHasher;
        this.executionEngine = executionEngine;
        this.inputFingerprinter = inputFingerprinter;
        this.listenerManager = listenerManager;
        this.reservedFileSystemLocationRegistry = reservedFileSystemLocationRegistry;
        this.fileCollectionFactory = fileCollectionFactory;
        this.fileOperations = fileOperations;
        this.taskInputsListeners = taskInputsListeners;
    }

    @Override
    public TaskExecuterResult execute(TaskInternal task, TaskStateInternal state, TaskExecutionContext context) {
        boolean emitLegacySnapshottingOperations = buildCacheState == BuildCacheState.ENABLED || scanPluginState == ScanPluginState.APPLIED;
        TaskExecution work = new TaskExecution(
                task,
                context,
                emitLegacySnapshottingOperations,

                actionListener,
                asyncWorkTracker,
                buildOperationExecutor,
                classLoaderHierarchyHasher,
                executionHistoryStore,
                fileCollectionFactory,
                fileOperations,
                inputFingerprinter,
                listenerManager,
                reservedFileSystemLocationRegistry,
                taskCacheabilityResolver,
                taskInputsListeners
        );
        try {
            return executeIfValid(task, state, context, work);
        } catch (WorkValidationException ex) {
            state.setOutcome(ex);
            return TaskExecuterResult.WITHOUT_OUTPUTS;
        }
    }

    private TaskExecuterResult executeIfValid(TaskInternal task, TaskStateInternal state, TaskExecutionContext context, TaskExecution work) {
        ExecutionEngine.Request request = executionEngine.createRequest(work);
        context.getTaskExecutionMode().getRebuildReason().ifPresent(request::forceNonIncremental);
        request.withValidationContext(context.getValidationContext());
        Result result = request.execute();
        result.getExecutionResult().ifSuccessfulOrElse(
                executionResult -> state.setOutcome(TaskExecutionOutcome.valueOf(executionResult.getOutcome())),
                failure -> state.setOutcome(new TaskExecutionException(task, failure))
        );
        return new TaskExecuterResult() {
            @Override
            public Optional<OriginMetadata> getReusedOutputOriginMetadata() {
                return result.getReusedOutputOriginMetadata();
            }

            @Override
            public boolean executedIncrementally() {
                return result.getExecutionResult()
                        .map(executionResult -> executionResult.getOutcome() == ExecutionOutcome.EXECUTED_INCREMENTALLY)
                        .getOrMapFailure(throwable -> false);
            }

            @Override
            public List<String> getExecutionReasons() {
                return result.getExecutionReasons();
            }

            @Override
            public CachingState getCachingState() {
                return result.getCachingState();
            }
        };
    }
}
