package org.gradle.execution;

import org.gradle.StartParameter;
import org.gradle.api.execution.TaskActionListener;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.execution.internal.TaskInputsListeners;
import org.gradle.api.internal.changedetection.TaskExecutionModeResolver;
import org.gradle.api.internal.changedetection.changes.DefaultTaskExecutionModeResolver;
import org.gradle.api.internal.changedetection.state.LineEndingNormalizingFileSystemLocationSnapshotHasher;
import org.gradle.api.internal.changedetection.state.ResourceEntryFilter;
import org.gradle.api.internal.changedetection.state.ResourceFilter;
import org.gradle.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.execution.CatchExceptionTaskExecuter;
import org.gradle.api.internal.tasks.execution.CleanupStaleOutputsExecuter;
import org.gradle.api.internal.tasks.execution.DefaultTaskCacheabilityResolver;
import org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter;
import org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter;
import org.gradle.api.internal.tasks.execution.FinalizePropertiesTaskExecuter;
import org.gradle.api.internal.tasks.execution.ResolveTaskExecutionModeExecuter;
import org.gradle.api.internal.tasks.execution.SkipOnlyIfTaskExecuter;
import org.gradle.api.internal.tasks.execution.SkipTaskWithNoActionsExecuter;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.tasks.execution.TaskCacheabilityResolver;
import org.gradle.caching.internal.controller.BuildCacheController;
import org.gradle.execution.plan.ExecutionNodeAccessHierarchies;
import org.gradle.execution.taskgraph.TaskExecutionGraphInternal;
import org.gradle.execution.taskgraph.TaskListenerInternal;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.initialization.DefaultBuildCancellationToken;
import org.gradle.internal.enterprise.core.GradleEnterprisePluginManager;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.execution.BuildOutputCleanupRegistry;
import org.gradle.internal.execution.ExecutionEngine;
import org.gradle.internal.execution.OutputChangeListener;
import org.gradle.internal.execution.fingerprint.FileCollectionFingerprinterRegistry;
import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.execution.fingerprint.InputFingerprinter;
import org.gradle.internal.execution.fingerprint.impl.DefaultFileCollectionFingerprinterRegistry;
import org.gradle.internal.execution.fingerprint.impl.FingerprinterRegistration;
import org.gradle.internal.execution.history.ExecutionHistoryStore;
import org.gradle.internal.execution.history.OutputFilesRepository;
import org.gradle.internal.file.DefaultReservedFileSystemLocationRegistry;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.file.FileAccessTracker;
import org.gradle.internal.file.RelativeFilePathResolver;
import org.gradle.internal.file.ReservedFileSystemLocation;
import org.gradle.internal.file.ReservedFileSystemLocationRegistry;
import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.LineEndingSensitivity;
import org.gradle.internal.fingerprint.classpath.impl.DefaultClasspathFingerprinter;
import org.gradle.internal.fingerprint.classpath.impl.DefaultCompileClasspathFingerprinter;
import org.gradle.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import org.gradle.internal.fingerprint.impl.AbsolutePathFileCollectionFingerprinter;
import org.gradle.internal.fingerprint.impl.DefaultInputFingerprinter;
import org.gradle.internal.fingerprint.impl.FileCollectionFingerprinterRegistrations;
import org.gradle.internal.fingerprint.impl.RelativePathFileCollectionFingerprinter;
import org.gradle.internal.hash.ChecksumService;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.resource.local.DefaultPathKeyFileStore;
import org.gradle.internal.resource.local.PathKeyFileStore;
import org.gradle.internal.service.scopes.ExecutionGradleServices;
import org.gradle.internal.snapshot.ValueSnapshotter;
import org.gradle.internal.work.AsyncWorkTracker;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class ProjectExecutionServices extends DefaultServiceRegistry {

    public ProjectExecutionServices(ProjectInternal project) {
        super("Configured project services for '" + project.getPath() + "'", project.getServices());
    }

    TaskActionListener createTaskActionListener(ListenerManager listenerManager) {
        return listenerManager.getBroadcaster(TaskActionListener.class);
    }

    TaskCacheabilityResolver createTaskCacheabilityResolver(RelativeFilePathResolver relativeFilePathResolver) {
        return new DefaultTaskCacheabilityResolver(relativeFilePathResolver);
    }

    ReservedFileSystemLocationRegistry createReservedFileLocationRegistry(List<ReservedFileSystemLocation> reservedFileSystemLocations) {
        return new DefaultReservedFileSystemLocationRegistry(reservedFileSystemLocations);
    }

    FileAccessTracker createFileAccessTracker() {
        return file -> {

        };
    }

    FileSystemLocationSnapshotHasher createFileSystemLocationSnapshotHasher() {
        return LineEndingNormalizingFileSystemLocationSnapshotHasher.DEFAULT;
    }

    FileCollectionFingerprinterRegistrations createFileCollectionFingerprinterRegistrations(
            StringInterner stringInterner,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            ResourceSnapshotterCacheService resourceSnapshotterCacheService
//            InputNormalizationHandlerInternal inputNormalizationHandler
    ) {
        return new FileCollectionFingerprinterRegistrations(
                stringInterner,
                fileCollectionSnapshotter,
                resourceSnapshotterCacheService,
//                inputNormalizationHandler.getRuntimeClasspath().getClasspathResourceFilter(),
//                inputNormalizationHandler.getRuntimeClasspath().getManifestAttributeResourceEntryFilter(),
//                inputNormalizationHandler.getRuntimeClasspath().getPropertiesFileFilters()
                ResourceFilter.FILTER_NOTHING,
                ResourceEntryFilter.FILTER_NOTHING,
                new HashMap<>()
        );
    }

    FileCollectionFingerprinterRegistry createFileCollectionFingerprinterRegistry(
            FileCollectionFingerprinterRegistrations fileCollectionFingerprinterRegistrations) {
        return new DefaultFileCollectionFingerprinterRegistry(fileCollectionFingerprinterRegistrations.getRegistrants());
    }

    InputFingerprinter createInputFingerprinter(
            FileCollectionSnapshotter fileCollectionSnapshotter,
            FileCollectionFingerprinterRegistry fileCollectionFingerprinterRegistry,
            ValueSnapshotter valueSnapshotter
    ) {
        return new DefaultInputFingerprinter(
                fileCollectionSnapshotter,
                fileCollectionFingerprinterRegistry,
                valueSnapshotter
        );
    }

    TaskExecutionModeResolver createExecutionModeResolver(
            StartParameter startParameter
    ) {
        return new DefaultTaskExecutionModeResolver(startParameter);
    }

    TaskExecuter createTaskExecuter(
            AsyncWorkTracker asyncWorkTracker,
            BuildCacheController buildCacheController,
            BuildOperationExecutor buildOperationExecutor,
            BuildOutputCleanupRegistry cleanupRegistry,
            GradleEnterprisePluginManager gradleEnterprisePluginManager,
            ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
            Deleter deleter,
            ExecutionHistoryStore executionHistoryStore,
            FileCollectionFactory fileCollectionFactory,
            FileOperations fileOperations,
            ListenerManager listenerManager,
            OutputChangeListener outputChangeListener,
            OutputFilesRepository outputFilesRepository,
            ReservedFileSystemLocationRegistry reservedFileSystemLocationRegistry,
            org.gradle.api.execution.TaskActionListener actionListener,
            TaskCacheabilityResolver taskCacheabilityResolver,
            TaskExecutionGraphInternal taskExecutionGraph,
            org.gradle.api.execution.TaskExecutionListener taskExecutionListener,
            TaskExecutionModeResolver repository,
            TaskListenerInternal taskListenerInternal,
            ExecutionEngine executionEngine,
            InputFingerprinter inputFingerprinter
    ) {
        TaskExecuter executer = new ExecuteActionsTaskExecuter(
                buildCacheController.isEnabled()
                        ? ExecuteActionsTaskExecuter.BuildCacheState.ENABLED
                        : ExecuteActionsTaskExecuter.BuildCacheState.DISABLED,
                gradleEnterprisePluginManager.isPresent()
                        ? ExecuteActionsTaskExecuter.ScanPluginState.APPLIED
                        : ExecuteActionsTaskExecuter.ScanPluginState.NOT_APPLIED,
                executionHistoryStore,
                buildOperationExecutor,
                asyncWorkTracker,
                actionListener,
                taskCacheabilityResolver,
                classLoaderHierarchyHasher,
                executionEngine,
                inputFingerprinter,
                listenerManager,
                reservedFileSystemLocationRegistry,
                fileCollectionFactory,
                fileOperations
        );
        executer = new CleanupStaleOutputsExecuter(
                buildOperationExecutor,
                cleanupRegistry,
                deleter,
                outputChangeListener,
                outputFilesRepository,
                executer
        );
        executer = new FinalizePropertiesTaskExecuter(executer);
        executer = new ResolveTaskExecutionModeExecuter(repository, executer);
        executer = new SkipTaskWithNoActionsExecuter(taskExecutionGraph, executer);
        executer = new SkipOnlyIfTaskExecuter(executer);
        executer = new CatchExceptionTaskExecuter(executer);
        executer = new EventFiringTaskExecuter(buildOperationExecutor, taskExecutionListener, taskListenerInternal, executer);
        return executer;
    }

    ExecutionNodeAccessHierarchies.InputNodeAccessHierarchy createInputNodeAccessHierarchies(ExecutionNodeAccessHierarchies hierarchies) {
        return hierarchies.createInputHierarchy();
    }
}
