package com.tyron.builder.internal.service.scopes;

import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.api.internal.changedetection.state.DefaultExecutionHistoryCacheAccess;
import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.scopes.BuildScopedCache;
import com.tyron.builder.caching.internal.controller.BuildCacheCommandFactory;
import com.tyron.builder.caching.internal.controller.BuildCacheController;
import com.tyron.builder.concurrent.ParallelismConfiguration;
import com.tyron.builder.execution.plan.DefaultPlanExecutor;
import com.tyron.builder.execution.plan.PlanExecutor;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.enterprise.core.GradleEnterprisePluginManager;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.BuildOutputCleanupRegistry;
import com.tyron.builder.internal.execution.ExecutionEngine;
import com.tyron.builder.internal.execution.OutputChangeListener;
import com.tyron.builder.internal.execution.OutputSnapshotter;
import com.tyron.builder.internal.execution.history.ExecutionHistoryCacheAccess;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.OutputFilesRepository;
import com.tyron.builder.internal.execution.history.OutputsCleaner;
import com.tyron.builder.internal.execution.history.OverlappingOutputDetector;
import com.tyron.builder.internal.execution.history.changes.ExecutionStateChangeDetector;
import com.tyron.builder.internal.execution.history.impl.DefaultExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.impl.DefaultOutputFilesRepository;
import com.tyron.builder.internal.execution.impl.DefaultExecutionEngine;
import com.tyron.builder.internal.execution.steps.AssignWorkspaceStep;
import com.tyron.builder.internal.execution.steps.BroadcastChangingOutputsStep;
import com.tyron.builder.internal.execution.steps.BuildCacheStep;
import com.tyron.builder.internal.execution.steps.CancelExecutionStep;
import com.tyron.builder.internal.execution.steps.CaptureStateAfterExecutionStep;
import com.tyron.builder.internal.execution.steps.CaptureStateBeforeExecutionStep;
import com.tyron.builder.internal.execution.steps.CreateOutputsStep;
import com.tyron.builder.internal.execution.steps.ExecuteStep;
import com.tyron.builder.internal.execution.steps.IdentifyStep;
import com.tyron.builder.internal.execution.steps.IdentityCacheStep;
import com.tyron.builder.internal.execution.steps.LoadPreviousExecutionStateStep;
import com.tyron.builder.internal.execution.steps.RecordOutputsStep;
import com.tyron.builder.internal.execution.steps.RemovePreviousOutputsStep;
import com.tyron.builder.internal.execution.steps.RemoveUntrackedExecutionStateStep;
import com.tyron.builder.internal.execution.steps.ResolveCachingStateStep;
import com.tyron.builder.internal.execution.steps.ResolveChangesStep;
import com.tyron.builder.internal.execution.steps.ResolveInputChangesStep;
import com.tyron.builder.internal.execution.steps.SkipEmptyWorkStep;
import com.tyron.builder.internal.execution.steps.SkipUpToDateStep;
import com.tyron.builder.internal.execution.steps.StoreExecutionStateStep;
import com.tyron.builder.internal.execution.steps.TimeoutStep;
import com.tyron.builder.internal.execution.steps.ValidateStep;
import com.tyron.builder.internal.execution.steps.WorkInputListeners;
import com.tyron.builder.internal.execution.steps.legacy.MarkSnapshottingInputsFinishedStep;
import com.tyron.builder.internal.execution.steps.legacy.MarkSnapshottingInputsStartedStep;
import com.tyron.builder.internal.execution.timeout.TimeoutHandler;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.internal.scopeids.id.BuildInvocationScopeId;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.builder.internal.work.WorkerLeaseService;
import com.tyron.builder.util.GradleVersion;

import java.util.Collections;
import java.util.function.Supplier;

public class ExecutionGradleServices {

    public ExecutionGradleServices() {
    }

    ExecutionHistoryCacheAccess createCacheAccess(BuildScopedCache cacheRepository) {
        return new DefaultExecutionHistoryCacheAccess(cacheRepository);
    }

    ExecutionHistoryStore createExecutionHistoryStore(
            ExecutionHistoryCacheAccess executionHistoryCacheAccess,
            InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory,
            StringInterner stringInterner
    ) {
        return new DefaultExecutionHistoryStore(
                executionHistoryCacheAccess,
                inMemoryCacheDecoratorFactory,
                stringInterner
        );
    }

    OutputFilesRepository createOutputFilesRepository(BuildScopedCache cacheRepository, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory) {
        PersistentCache cacheAccess = cacheRepository
                .crossVersionCache("buildOutputCleanup")
                .withCrossVersionCache(CacheBuilder.LockTarget.DefaultTarget)
                .withDisplayName("Build Output Cleanup Cache")
                .withLockOptions(mode(FileLockManager.LockMode.OnDemand))
                .withProperties(Collections.singletonMap("gradle.version", GradleVersion.current().getVersion()))
                .open();
        return new DefaultOutputFilesRepository(cacheAccess, inMemoryCacheDecoratorFactory);
    }

    PlanExecutor createPlanExecutor(
            ParallelismConfiguration parallelismConfiguration,
            ExecutorFactory executorFactory,
            WorkerLeaseService workerLeaseService,
            BuildCancellationToken cancellationToken,
            ResourceLockCoordinationService coordinationService) {
        int parallelThreads = parallelismConfiguration.getMaxWorkerCount();
        if (parallelThreads < 1) {
            throw new IllegalStateException(String.format("Cannot create executor for requested number of worker threads: %s.", parallelThreads));
        }

        return new DefaultPlanExecutor(
                parallelismConfiguration,
                executorFactory,
                workerLeaseService,
                cancellationToken,
                coordinationService
        );
    }

    OutputChangeListener createOutputChangeListener(ListenerManager listenerManager) {
        return listenerManager.getBroadcaster(OutputChangeListener.class);
    }

    ValidateStep.ValidationWarningRecorder createWarningRecorder() {
        return (work, warnings) -> System.out.println(warnings);
    }

    public ExecutionEngine createExecutionEngine(
        BuildCacheCommandFactory buildCacheCommandFactory,
        BuildCacheController buildCacheController,
        BuildCancellationToken cancellationToken,
        BuildInvocationScopeId buildInvocationScopeId,
        BuildOperationExecutor buildOperationExecutor,
        BuildOutputCleanupRegistry buildOutputCleanupRegistry,
        GradleEnterprisePluginManager gradleEnterprisePluginManager,
        ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
        CurrentBuildOperationRef currentBuildOperationRef,
        Deleter deleter,
        ExecutionStateChangeDetector changeDetector,
        OutputChangeListener outputChangeListener,
        OutputFilesRepository outputFilesRepository,
        OutputSnapshotter outputSnapshotter,
        OverlappingOutputDetector overlappingOutputDetector,
        TimeoutHandler timeoutHandler,
        ValidateStep.ValidationWarningRecorder validationWarningRecorder,
        VirtualFileSystem virtualFileSystem,
        DocumentationRegistry documentationRegistry
    ) {
        Supplier<OutputsCleaner> skipEmptyWorkOutputsCleanerSupplier = () -> new OutputsCleaner(deleter, buildOutputCleanupRegistry::isOutputOwnedByBuild, buildOutputCleanupRegistry::isOutputOwnedByBuild);
        // @formatter:off
        return new DefaultExecutionEngine(documentationRegistry,
            new IdentifyStep<>(
            new IdentityCacheStep<>(
            new AssignWorkspaceStep<>(
            new LoadPreviousExecutionStateStep<>(
            new MarkSnapshottingInputsStartedStep<>(
            new RemoveUntrackedExecutionStateStep<>(
            new SkipEmptyWorkStep(outputChangeListener, skipEmptyWorkOutputsCleanerSupplier,
            new CaptureStateBeforeExecutionStep<>(buildOperationExecutor, classLoaderHierarchyHasher, outputSnapshotter, overlappingOutputDetector,
            new ValidateStep<>(virtualFileSystem, validationWarningRecorder,
            new ResolveCachingStateStep<>(buildCacheController, false,
            new MarkSnapshottingInputsFinishedStep<>(
            new ResolveChangesStep<>(changeDetector,
            new SkipUpToDateStep<>(
            new RecordOutputsStep<>(outputFilesRepository,
            new StoreExecutionStateStep<>(
            new BuildCacheStep(buildCacheController, buildCacheCommandFactory, deleter, outputChangeListener,
            new BroadcastChangingOutputsStep<>(outputChangeListener,
            new CaptureStateAfterExecutionStep<>(buildOperationExecutor, buildInvocationScopeId.getId(), outputSnapshotter,
            new CreateOutputsStep<>(
            new TimeoutStep<>(timeoutHandler, currentBuildOperationRef,
            new CancelExecutionStep<>(cancellationToken,
            new ResolveInputChangesStep<>(
            new RemovePreviousOutputsStep<>(deleter, outputChangeListener,
            new ExecuteStep<>(buildOperationExecutor
        )))))))))))))))))))))))));
        // @formatter:on
    }
}
