package com.tyron.builder.execution;

import com.tyron.builder.api.execution.TaskExecutionGraph;
import com.tyron.builder.api.execution.TaskExecutionListener;
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
import com.tyron.builder.api.internal.tasks.execution.EventFiringTaskExecuter;
import com.tyron.builder.api.internal.tasks.execution.ExecuteActionsTaskExecuter;
import com.tyron.builder.api.internal.tasks.execution.FinalizePropertiesTaskExecuter;
import com.tyron.builder.api.internal.tasks.execution.ResolveTaskExecutionModeExecuter;
import com.tyron.builder.api.internal.tasks.execution.SkipOnlyIfTaskExecuter;
import com.tyron.builder.api.internal.tasks.execution.SkipTaskWithNoActionsExecuter;
import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.execution.taskgraph.TaskListenerInternal;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.initialization.DefaultBuildCancellationToken;
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
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.file.FileAccessTracker;
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

    private final ProjectInternal projectInternal;

    public ProjectExecutionServices(ProjectInternal project) {
        super("Configured project services for '" + project.getPath() + "'", project.getServices());

        this.projectInternal = project;

        BuildCancellationToken token = new DefaultBuildCancellationToken();
        add(BuildCancellationToken.class, token);
    }

    FileAccessTracker createFileAccessTracker() {
        return file -> {

        };
    }

    PathKeyFileStore createPathKeyFileStore(
            ChecksumService checksumService
    ) {
        return new DefaultPathKeyFileStore(checksumService, projectInternal.getBuildDir());
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

    protected TaskExecuter createTaskExecuter(
            TaskExecutionGraph taskExecutionGraph,
            ExecutionHistoryStore executionHistoryStore,
            BuildOperationExecutor buildOperationExecutor,
            BuildOutputCleanupRegistry cleanupRegistry,
            Deleter deleter,
            OutputChangeListener outputChangeListener,
            OutputFilesRepository outputFilesRepository,
            AsyncWorkTracker asyncWorkTracker,
            ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
            ExecutionEngine executionEngine,
            InputFingerprinter inputFingerprinter,
            ListenerManager listenerManager,
            FileCollectionFactory factory,
            TaskExecutionListener taskExecutionListener,
            TaskListenerInternal taskListenerInternal,
            FileOperations fileOperations
    ) {
        TaskExecuter executer = new ExecuteActionsTaskExecuter(
                ExecuteActionsTaskExecuter.BuildCacheState.ENABLED,
                executionHistoryStore,
                buildOperationExecutor,
                asyncWorkTracker,
                classLoaderHierarchyHasher,
                executionEngine,
                inputFingerprinter,
                listenerManager,
                factory,
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
        executer = new ResolveTaskExecutionModeExecuter(executer);
        executer = new SkipTaskWithNoActionsExecuter(executer, taskExecutionGraph);
        executer = new SkipOnlyIfTaskExecuter(executer);
        executer = new CatchExceptionTaskExecuter(executer);
        executer = new EventFiringTaskExecuter(buildOperationExecutor, taskExecutionListener, taskListenerInternal, executer);
        return executer;
    }

    ExecutionNodeAccessHierarchies.InputNodeAccessHierarchy createInputNodeAccessHierarchies(ExecutionNodeAccessHierarchies hierarchies) {
        return hierarchies.createInputHierarchy();
    }
}
