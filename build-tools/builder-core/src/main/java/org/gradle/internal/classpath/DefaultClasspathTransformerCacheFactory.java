package org.gradle.internal.classpath;

import static org.gradle.cache.internal.CacheVersionMapping.introducedIn;
import static org.gradle.cache.internal.LeastRecentlyUsedCacheCleanup.DEFAULT_MAX_AGE_IN_DAYS_FOR_RECREATABLE_CACHE_ENTRIES;
import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode;

import com.google.common.annotations.VisibleForTesting;
import org.gradle.cache.CacheBuilder;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.internal.CacheVersionMapping;
import org.gradle.cache.internal.CompositeCleanupAction;
import org.gradle.cache.internal.LeastRecentlyUsedCacheCleanup;
import org.gradle.cache.internal.SingleDepthFilesFinder;
import org.gradle.cache.internal.UnusedVersionsCacheCleanup;
import org.gradle.cache.internal.UsedGradleVersions;
import org.gradle.cache.scopes.GlobalScopedCache;
import org.gradle.internal.file.FileAccessTimeJournal;
import org.gradle.internal.file.FileAccessTracker;
import org.gradle.internal.file.impl.SingleDepthFileAccessTracker;

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
