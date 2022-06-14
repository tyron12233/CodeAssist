package com.tyron.builder.execution;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.execution.TaskActionListener;
import com.tyron.builder.api.execution.TaskExecutionGraph;
import com.tyron.builder.api.execution.TaskExecutionListener;
import com.tyron.builder.api.execution.internal.TaskInputsListeners;
import com.tyron.builder.api.internal.changedetection.TaskExecutionModeResolver;
import com.tyron.builder.api.internal.changedetection.changes.DefaultTaskExecutionModeResolver;
import com.tyron.builder.api.internal.changedetection.state.LineEndingNormalizingFileSystemLocationSnapshotHasher;
import com.tyron.builder.api.internal.changedetection.state.ResourceEntryFilter;
import com.tyron.builder.api.internal.changedetection.state.ResourceFilter;
import com.tyron.builder.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.execution.CatchExceptionTaskExecuter;
import com.tyron.builder.api.internal.tasks.execution.CleanupStaleOutputsExecuter;
import com.tyron.builder.api.internal.tasks.execution.DefaultTaskCacheabilityResolver;
import com.tyron.builder.api.internal.tasks.execution.EventFiringTaskExecuter;
import com.tyron.builder.api.internal.tasks.execution.ExecuteActionsTaskExecuter;
import com.tyron.builder.api.internal.tasks.execution.FinalizePropertiesTaskExecuter;
import com.tyron.builder.api.internal.tasks.execution.ResolveTaskExecutionModeExecuter;
import com.tyron.builder.api.internal.tasks.execution.SkipOnlyIfTaskExecuter;
import com.tyron.builder.api.internal.tasks.execution.SkipTaskWithNoActionsExecuter;
import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.api.internal.tasks.execution.TaskCacheabilityResolver;
import com.tyron.builder.caching.internal.controller.BuildCacheController;
import com.tyron.builder.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.execution.taskgraph.TaskExecutionGraphInternal;
import com.tyron.builder.execution.taskgraph.TaskListenerInternal;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.initialization.DefaultBuildCancellationToken;
import com.tyron.builder.internal.enterprise.core.GradleEnterprisePluginManager;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.BuildOutputCleanupRegistry;
import com.tyron.builder.internal.execution.ExecutionEngine;
import com.tyron.builder.internal.execution.OutputChangeListener;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionFingerprinterRegistry;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.internal.execution.fingerprint.impl.DefaultFileCollectionFingerprinterRegistry;
import com.tyron.builder.internal.execution.fingerprint.impl.FingerprinterRegistration;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.OutputFilesRepository;
import com.tyron.builder.internal.file.DefaultReservedFileSystemLocationRegistry;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.file.FileAccessTracker;
import com.tyron.builder.internal.file.RelativeFilePathResolver;
import com.tyron.builder.internal.file.ReservedFileSystemLocation;
import com.tyron.builder.internal.file.ReservedFileSystemLocationRegistry;
import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.internal.fingerprint.classpath.impl.DefaultClasspathFingerprinter;
import com.tyron.builder.internal.fingerprint.classpath.impl.DefaultCompileClasspathFingerprinter;
import com.tyron.builder.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import com.tyron.builder.internal.fingerprint.impl.AbsolutePathFileCollectionFingerprinter;
import com.tyron.builder.internal.fingerprint.impl.DefaultInputFingerprinter;
import com.tyron.builder.internal.fingerprint.impl.RelativePathFileCollectionFingerprinter;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.resource.local.DefaultPathKeyFileStore;
import com.tyron.builder.internal.resource.local.PathKeyFileStore;
import com.tyron.builder.internal.service.scopes.ExecutionGradleServices;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;
import com.tyron.builder.internal.work.AsyncWorkTracker;

import java.util.Collections;
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

    FileCollectionFingerprinterRegistry createFileCollectionFingerprinterRegistry(
            List<FingerprinterRegistration> registrations,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            FileSystemLocationSnapshotHasher fileSystemLocationSnapshotHasher
    ){
        return new DefaultFileCollectionFingerprinterRegistry(registrations);
    }

    FingerprinterRegistration createAbsolutePathDefaultFingerprinter(
            FileCollectionSnapshotter fileCollectionSnapshotter,
            FileSystemLocationSnapshotHasher fileSystemLocationSnapshotHasher
    ) {
        return FingerprinterRegistration.registration(
                DirectorySensitivity.DEFAULT,
                LineEndingSensitivity.DEFAULT,
                new AbsolutePathFileCollectionFingerprinter(
                        DirectorySensitivity.DEFAULT,
                        fileCollectionSnapshotter,
                        fileSystemLocationSnapshotHasher
                )
        );
    }

    FingerprinterRegistration createAbsolutePathIgnoreDirectoryFingerprinter(
            FileCollectionSnapshotter fileCollectionSnapshotter,
            FileSystemLocationSnapshotHasher fileSystemLocationSnapshotHasher
    ) {
        return FingerprinterRegistration.registration(
                DirectorySensitivity.IGNORE_DIRECTORIES,
                LineEndingSensitivity.DEFAULT,
                new AbsolutePathFileCollectionFingerprinter(
                        DirectorySensitivity.IGNORE_DIRECTORIES,
                        fileCollectionSnapshotter,
                        fileSystemLocationSnapshotHasher
                )
        );
    }

    FingerprinterRegistration createIgnoreDirectoryNormalizeLineEndingsRelativePathInputFingerprinter(
            FileSystemLocationSnapshotHasher hasher,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            StringInterner interner
    ) {
        return FingerprinterRegistration.registration(
                DirectorySensitivity.IGNORE_DIRECTORIES,
                LineEndingSensitivity.NORMALIZE_LINE_ENDINGS,
                new RelativePathFileCollectionFingerprinter(
                        interner,
                        DirectorySensitivity.IGNORE_DIRECTORIES,
                        fileCollectionSnapshotter,
                        hasher
                )
        );
    }

    FingerprinterRegistration createIgnoreDirectoryDefaultLineEndingsRelativePathInputFingerprinter(
            FileSystemLocationSnapshotHasher hasher,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            StringInterner interner
    ) {
        return FingerprinterRegistration.registration(
                DirectorySensitivity.IGNORE_DIRECTORIES,
                LineEndingSensitivity.DEFAULT,
                new RelativePathFileCollectionFingerprinter(
                        interner,
                        DirectorySensitivity.IGNORE_DIRECTORIES,
                        fileCollectionSnapshotter,
                        hasher
                )
        );
    }

    FingerprinterRegistration createCompileClassPathFingerprinter(
            ResourceSnapshotterCacheService resourceSnapshotterCacheService,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            StringInterner interner
    ) {
        return FingerprinterRegistration.registration(
                DirectorySensitivity.DEFAULT,
                LineEndingSensitivity.DEFAULT,
                new DefaultCompileClasspathFingerprinter(
                        resourceSnapshotterCacheService,
                        fileCollectionSnapshotter,
                        interner
                )
        );
    }

    FingerprinterRegistration createClassPathNormalizerIgnoreDirectoriesInputFingerprinter(
            ResourceSnapshotterCacheService resourceSnapshotterCacheService,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            StringInterner interner
    ) {
        return FingerprinterRegistration.registration(
                DirectorySensitivity.IGNORE_DIRECTORIES,
                LineEndingSensitivity.DEFAULT,
                new DefaultClasspathFingerprinter(
                        resourceSnapshotterCacheService,
                        fileCollectionSnapshotter,
                        ResourceFilter.FILTER_NOTHING,
                        ResourceEntryFilter.FILTER_NOTHING,
                        Collections.emptyMap(),
                        interner,
                        LineEndingSensitivity.DEFAULT
                )
        );
    }


    FingerprinterRegistration createClassPathNormalizerInputFingerprinter(
            ResourceSnapshotterCacheService resourceSnapshotterCacheService,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            StringInterner interner
    ) {
        return FingerprinterRegistration.registration(
                DirectorySensitivity.DEFAULT,
                LineEndingSensitivity.DEFAULT,
                new DefaultClasspathFingerprinter(
                        resourceSnapshotterCacheService,
                        fileCollectionSnapshotter,
                        ResourceFilter.FILTER_NOTHING,
                        ResourceEntryFilter.FILTER_NOTHING,
                        Collections.emptyMap(),
                        interner,
                        LineEndingSensitivity.DEFAULT
                )
        );
    }

    FingerprinterRegistration createRelativePathDefaultDefaultFingerprinter(
            FileSystemLocationSnapshotHasher hasher,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            StringInterner interner
    ) {
        return FingerprinterRegistration.registration(
                DirectorySensitivity.DEFAULT,
                LineEndingSensitivity.DEFAULT,
                new RelativePathFileCollectionFingerprinter(
                        interner,
                        DirectorySensitivity.DEFAULT,
                        fileCollectionSnapshotter,
                        hasher
                )
        );
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
            TaskActionListener actionListener,
            TaskCacheabilityResolver taskCacheabilityResolver,
            TaskExecutionGraphInternal taskExecutionGraph,
            TaskExecutionListener taskExecutionListener,
            TaskExecutionModeResolver repository,
            TaskInputsListeners taskInputsListeners,
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
                fileOperations,
                taskInputsListeners
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
