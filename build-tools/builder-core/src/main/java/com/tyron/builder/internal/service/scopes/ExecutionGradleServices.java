package com.tyron.builder.internal.service.scopes;

import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.changedetection.state.DefaultExecutionHistoryCacheAccess;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.BuildOutputCleanupRegistry;
import com.tyron.builder.internal.execution.ExecutionEngine;
import com.tyron.builder.internal.execution.OutputChangeListener;
import com.tyron.builder.internal.execution.OutputSnapshotter;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.internal.execution.history.ExecutionHistoryCacheAccess;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.OutputFilesRepository;
import com.tyron.builder.internal.execution.history.OutputsCleaner;
import com.tyron.builder.internal.execution.history.OverlappingOutputDetector;
import com.tyron.builder.internal.execution.history.changes.DefaultExecutionStateChangeDetector;
import com.tyron.builder.internal.execution.history.changes.ExecutionStateChangeDetector;
import com.tyron.builder.internal.execution.history.impl.DefaultExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.impl.DefaultOutputFilesRepository;
import com.tyron.builder.internal.execution.history.impl.DefaultOverlappingOutputDetector;
import com.tyron.builder.internal.execution.impl.DefaultExecutionEngine;
import com.tyron.builder.internal.execution.impl.DefaultOutputSnapshotter;
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
import com.tyron.builder.internal.execution.steps.MarkSnapshottingInputsStartedStep;
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
import com.tyron.builder.internal.execution.timeout.TimeoutHandler;
import com.tyron.builder.internal.execution.timeout.impl.DefaultTimeoutHandler;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.file.Stat;
import com.tyron.builder.internal.fingerprint.impl.DefaultGenericFileTreeSnapshotter;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.internal.fingerprint.GenericFileTreeSnapshotter;
import com.tyron.builder.internal.fingerprint.impl.DefaultFileCollectionSnapshotter;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.hash.FileHasher;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.scopeids.id.BuildInvocationScopeId;
import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.StringInterner;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.scopes.BuildScopedCache;
import com.tyron.builder.caching.internal.BuildCacheController;
import com.tyron.builder.caching.internal.controller.DefaultBuildCacheController;
import com.tyron.builder.caching.internal.origin.OriginMetadataFactory;
import com.tyron.builder.caching.internal.packaging.BuildCacheEntryPacker;
import com.tyron.builder.caching.internal.service.BuildCacheServicesConfiguration;
import com.tyron.builder.caching.local.internal.LocalBuildCacheService;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.builder.internal.vfs.VirtualFileSystem;

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
            StringInterner stringInterner,
            ClassLoaderHierarchyHasher classLoaderHasher
    ) {
        return new DefaultExecutionHistoryStore(
                executionHistoryCacheAccess,
                inMemoryCacheDecoratorFactory,
                stringInterner,
                classLoaderHasher
        );
    }

    OutputFilesRepository createOutputFilesRepository(BuildScopedCache cacheRepository, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory) {
        PersistentCache cacheAccess = cacheRepository
                .crossVersionCache("buildOutputCleanup")
                .withCrossVersionCache(CacheBuilder.LockTarget.DefaultTarget)
                .withDisplayName("Build Output Cleanup Cache")
                .withLockOptions(mode(FileLockManager.LockMode.OnDemand))
                .withProperties(Collections.singletonMap("gradle.version", "0.0.1"))
                .open();
        return new DefaultOutputFilesRepository(cacheAccess, inMemoryCacheDecoratorFactory);
    }

    OutputChangeListener createOutputChangeListener(ListenerManager listenerManager) {
        return listenerManager.getBroadcaster(OutputChangeListener.class);
    }

    BuildCacheServicesConfiguration createBuildCacheServicesConfiguration(
            LocalBuildCacheService localBuildCacheService
    ) {
        return new BuildCacheServicesConfiguration(localBuildCacheService, true, null, false);
    }

    BuildCacheController createBuildCacheController(
            BuildCacheServicesConfiguration configuration,
            BuildOperationExecutor buildOperationExecutor,
            TemporaryFileProvider temporaryFileProvider,
            FileSystemAccess fileSystemAccess,
            BuildCacheEntryPacker buildCacheEntryPacker,
            OriginMetadataFactory originMetadataFactory,
            StringInterner stringInterner
    ) {
        return new DefaultBuildCacheController(
                configuration,
                buildOperationExecutor,
                temporaryFileProvider,
                true,
                true,
                true,
                fileSystemAccess,
                buildCacheEntryPacker,
                originMetadataFactory,
                stringInterner
        );
    }

    ExecutionStateChangeDetector createExecutionStateChangeDetector() {
        return new DefaultExecutionStateChangeDetector();
    }

    GenericFileTreeSnapshotter createGenericFileTreeSnapshotter(
            FileHasher fileHasher,
            StringInterner stringInterner
    ) {
        return new DefaultGenericFileTreeSnapshotter(fileHasher, stringInterner);
    }

    FileCollectionSnapshotter createFileCollectionSnapshotter(
            FileSystemAccess fileSystemAccess,
            GenericFileTreeSnapshotter genericFileTreeSnapshotter,
            Stat stat
    ) {
        return new DefaultFileCollectionSnapshotter(fileSystemAccess, genericFileTreeSnapshotter, stat);
    }

    OutputSnapshotter createOutputSnapshotter(
            FileCollectionSnapshotter fileCollectionSnapshotter
    ) {
        return new DefaultOutputSnapshotter(fileCollectionSnapshotter);
    }

    OverlappingOutputDetector createOverlappingOutputDetector() {
        return new DefaultOverlappingOutputDetector();
    }

    TimeoutHandler createTimeoutHandler(
            ExecutorFactory executorFactory
    ) {
        return new DefaultTimeoutHandler(
                executorFactory.createScheduled("TimeoutHandler", 1),
                CurrentBuildOperationRef.instance()
        );
    }

    ValidateStep.ValidationWarningRecorder createWarningRecorder() {
        return (work, warnings) -> System.out.println(warnings);
    }

    ExecutionEngine createExecutionEngine(
            BuildCacheController buildCacheController,
            BuildCancellationToken cancellationToken,
            BuildInvocationScopeId buildInvocationScopeId,
            BuildOperationExecutor buildOperationExecutor,
            BuildOutputCleanupRegistry buildOutputCleanupRegistry,
            ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
            CurrentBuildOperationRef currentBuildOperationRef,
            Deleter deleter,
            ExecutionStateChangeDetector changeDetector,
            OutputChangeListener outputChangeListener,
            WorkInputListeners workInputListeners, OutputFilesRepository outputFilesRepository,
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
                new SkipEmptyWorkStep(outputChangeListener, workInputListeners, skipEmptyWorkOutputsCleanerSupplier,
                new CaptureStateBeforeExecutionStep<>(buildOperationExecutor, classLoaderHierarchyHasher, outputSnapshotter, overlappingOutputDetector,
                new ValidateStep<>(virtualFileSystem, validationWarningRecorder,
                new ResolveCachingStateStep<>(buildCacheController, false,
                new MarkSnapshottingInputsFinishedStep<>(
                new ResolveChangesStep<>(changeDetector,
                new SkipUpToDateStep<>(
                new RecordOutputsStep<>(outputFilesRepository,
                new StoreExecutionStateStep<>(
                new BuildCacheStep(buildCacheController, deleter, outputChangeListener,
                new BroadcastChangingOutputsStep<>(outputChangeListener,
                new CaptureStateAfterExecutionStep<>(buildOperationExecutor, buildInvocationScopeId.getId(), outputSnapshotter,
                new CreateOutputsStep<>(
                new TimeoutStep<>(timeoutHandler, currentBuildOperationRef,
                new CancelExecutionStep<>(cancellationToken,
                new ResolveInputChangesStep<>(
                new RemovePreviousOutputsStep<>(deleter, outputChangeListener,
                new ExecuteStep<>(buildOperationExecutor)
        ))))))))))))))))))))))));
        // @formatter:off
    }
}
