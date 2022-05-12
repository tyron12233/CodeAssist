package com.tyron.builder.internal.classpath;

import static com.tyron.builder.cache.internal.CacheVersionMapping.introducedIn;
import static com.tyron.builder.cache.internal.LeastRecentlyUsedCacheCleanup.DEFAULT_MAX_AGE_IN_DAYS_FOR_RECREATABLE_CACHE_ENTRIES;
import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.internal.CacheVersionMapping;
import com.tyron.builder.cache.internal.CompositeCleanupAction;
import com.tyron.builder.cache.internal.LeastRecentlyUsedCacheCleanup;
import com.tyron.builder.cache.internal.SingleDepthFilesFinder;
import com.tyron.builder.cache.internal.UnusedVersionsCacheCleanup;
import com.tyron.builder.cache.internal.UsedGradleVersions;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.internal.file.FileAccessTimeJournal;
import com.tyron.builder.internal.file.FileAccessTracker;
import com.tyron.builder.internal.file.impl.SingleDepthFileAccessTracker;

public class DefaultClasspathTransformerCacheFactory implements ClasspathTransformerCacheFactory {
    private static final CacheVersionMapping CACHE_VERSION_MAPPING = introducedIn("2.2")
        .incrementedIn("3.2-rc-1")
        .incrementedIn("3.5-rc-1")
        .changedTo(8, "6.5-rc-1")
        .incrementedIn("7.1")
        .build();
    @VisibleForTesting
    static final String CACHE_NAME = "jars";
    @VisibleForTesting
    static final String CACHE_KEY = CACHE_NAME + "-" + CACHE_VERSION_MAPPING.getLatestVersion();
    private static final int FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP = 1;

    private final UsedGradleVersions usedGradleVersions;

    public DefaultClasspathTransformerCacheFactory(UsedGradleVersions usedGradleVersions) {
        this.usedGradleVersions = usedGradleVersions;
    }

    @Override
    public PersistentCache createCache(GlobalScopedCache cacheRepository, FileAccessTimeJournal fileAccessTimeJournal) {
        return cacheRepository
            .crossVersionCache(CACHE_KEY)
            .withDisplayName(CACHE_NAME)
            .withCrossVersionCache(CacheBuilder.LockTarget.DefaultTarget)
            .withLockOptions(mode(FileLockManager.LockMode.OnDemand))
            .withCleanup(
                CompositeCleanupAction.builder()
                    .add(UnusedVersionsCacheCleanup.create(CACHE_NAME, CACHE_VERSION_MAPPING, usedGradleVersions))
                    .add(
                        new LeastRecentlyUsedCacheCleanup(
                            new SingleDepthFilesFinder(FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP),
                            fileAccessTimeJournal,
                            DEFAULT_MAX_AGE_IN_DAYS_FOR_RECREATABLE_CACHE_ENTRIES
                        )
                    ).build()
            ).open();
    }

    @Override
    public FileAccessTracker createFileAccessTracker(PersistentCache persistentCache, FileAccessTimeJournal fileAccessTimeJournal) {
        return new SingleDepthFileAccessTracker(fileAccessTimeJournal, persistentCache.getBaseDir(), FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP);
    }
}
