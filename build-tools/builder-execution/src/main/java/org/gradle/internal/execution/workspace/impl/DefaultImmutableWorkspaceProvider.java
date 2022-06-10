package org.gradle.internal.execution.workspace.impl;

import org.gradle.api.internal.cache.StringInterner;
import org.gradle.cache.CacheBuilder;
import org.gradle.cache.CleanupAction;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.cache.internal.LeastRecentlyUsedCacheCleanup;
import org.gradle.cache.internal.SingleDepthFilesFinder;
import org.gradle.internal.execution.history.ExecutionHistoryStore;
import org.gradle.internal.execution.history.impl.DefaultExecutionHistoryStore;
import org.gradle.internal.execution.workspace.WorkspaceProvider;
import org.gradle.internal.file.FileAccessTimeJournal;
import org.gradle.internal.file.impl.SingleDepthFileAccessTracker;

import java.io.Closeable;
import java.io.File;
import java.util.function.Function;

import static org.gradle.cache.internal.LeastRecentlyUsedCacheCleanup.DEFAULT_MAX_AGE_IN_DAYS_FOR_RECREATABLE_CACHE_ENTRIES;
import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode;

public class DefaultImmutableWorkspaceProvider implements WorkspaceProvider, Closeable {
    private static final int DEFAULT_FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP = 1;

    private final SingleDepthFileAccessTracker fileAccessTracker;
    private final File baseDirectory;
    private final ExecutionHistoryStore executionHistoryStore;
    private final PersistentCache cache;

    public static DefaultImmutableWorkspaceProvider withBuiltInHistory(
        CacheBuilder cacheBuilder,
        FileAccessTimeJournal fileAccessTimeJournal,
        InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory,
        StringInterner stringInterner
    ) {
        return withBuiltInHistory(
            cacheBuilder,
            fileAccessTimeJournal,
            inMemoryCacheDecoratorFactory,
            stringInterner,
            DEFAULT_FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP
        );
    }

    public static DefaultImmutableWorkspaceProvider withBuiltInHistory(
        CacheBuilder cacheBuilder,
        FileAccessTimeJournal fileAccessTimeJournal,
        InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory,
        StringInterner stringInterner,
        int treeDepthToTrackAndCleanup
    ) {
        return new DefaultImmutableWorkspaceProvider(
            cacheBuilder,
            fileAccessTimeJournal,
            cache -> new DefaultExecutionHistoryStore(() -> cache, inMemoryCacheDecoratorFactory, stringInterner),
            treeDepthToTrackAndCleanup
        );
    }

    public static DefaultImmutableWorkspaceProvider withExternalHistory(
        CacheBuilder cacheBuilder,
        FileAccessTimeJournal fileAccessTimeJournal,
        ExecutionHistoryStore executionHistoryStore
    ) {
        return new DefaultImmutableWorkspaceProvider(
            cacheBuilder,
            fileAccessTimeJournal,
            __ -> executionHistoryStore,
            DEFAULT_FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP
        );
    }

    private DefaultImmutableWorkspaceProvider(
        CacheBuilder cacheBuilder,
        FileAccessTimeJournal fileAccessTimeJournal,
        Function<PersistentCache, ExecutionHistoryStore> historyFactory,
        int treeDepthToTrackAndCleanup
    ) {
        PersistentCache cache = cacheBuilder
            .withCleanup(createCleanupAction(fileAccessTimeJournal, treeDepthToTrackAndCleanup))
            .withLockOptions(mode(FileLockManager.LockMode.OnDemand)) // Lock on demand
            .open();
        this.cache = cache;
        this.baseDirectory = cache.getBaseDir();
        this.fileAccessTracker = new SingleDepthFileAccessTracker(fileAccessTimeJournal, baseDirectory, treeDepthToTrackAndCleanup);
        this.executionHistoryStore = historyFactory.apply(cache);
    }

    private static CleanupAction createCleanupAction(FileAccessTimeJournal fileAccessTimeJournal, int treeDepthToTrackAndCleanup) {
        return new LeastRecentlyUsedCacheCleanup(
            new SingleDepthFilesFinder(treeDepthToTrackAndCleanup),
            fileAccessTimeJournal,
            DEFAULT_MAX_AGE_IN_DAYS_FOR_RECREATABLE_CACHE_ENTRIES
        );
    }

    @Override
    public <T> T withWorkspace(String path, WorkspaceAction<T> action) {
        return cache.withFileLock(() -> {
            File workspace = new File(baseDirectory, path);
            fileAccessTracker.markAccessed(workspace);
            return action.executeInWorkspace(workspace, executionHistoryStore);
        });
    }

    @Override
    public void close() {
        cache.close();
    }
}
