package com.tyron.builder.internal.execution.workspace.impl;

import com.tyron.builder.api.internal.cache.StringInterner;
import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.CleanupAction;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.LeastRecentlyUsedCacheCleanup;
import com.tyron.builder.cache.internal.SingleDepthFilesFinder;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.impl.DefaultExecutionHistoryStore;
import com.tyron.builder.internal.execution.workspace.WorkspaceProvider;
import com.tyron.builder.internal.file.FileAccessTimeJournal;
import com.tyron.builder.internal.file.impl.SingleDepthFileAccessTracker;

import java.io.Closeable;
import java.io.File;
import java.util.function.Function;

import static com.tyron.builder.cache.internal.LeastRecentlyUsedCacheCleanup.DEFAULT_MAX_AGE_IN_DAYS_FOR_RECREATABLE_CACHE_ENTRIES;
import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

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
