package com.tyron.builder.caching.local.internal;

import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.internal.CleanupActionFactory;
import com.tyron.builder.cache.internal.LeastRecentlyUsedCacheCleanup;
import com.tyron.builder.cache.internal.SingleDepthFilesFinder;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.caching.BuildCacheService;
import com.tyron.builder.caching.BuildCacheServiceFactory;
import com.tyron.builder.caching.local.DirectoryBuildCache;
import com.tyron.builder.internal.file.FileAccessTimeJournal;
import com.tyron.builder.internal.file.FileAccessTracker;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.internal.file.impl.SingleDepthFileAccessTracker;
import com.tyron.builder.internal.resource.local.PathKeyFileStore;

import javax.inject.Inject;
import java.io.File;

import static com.tyron.builder.cache.FileLockManager.LockMode.OnDemand;
import static com.tyron.builder.cache.internal.filelock.LockOptionsBuilder.mode;

public class DirectoryBuildCacheServiceFactory implements BuildCacheServiceFactory<DirectoryBuildCache> {
    public static final String FAILED_READ_SUFFIX = ".failed";

    private static final String BUILD_CACHE_VERSION = "1";
    private static final String BUILD_CACHE_KEY = "build-cache-" + BUILD_CACHE_VERSION;
    private static final String DIRECTORY_BUILD_CACHE_TYPE = "directory";
    private static final int FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP = 1;

    private final CacheRepository cacheRepository;
    private final GlobalScopedCache globalScopedCache;
    private final PathToFileResolver resolver;
    private final DirectoryBuildCacheFileStoreFactory fileStoreFactory;
    private final CleanupActionFactory cleanupActionFactory;
    private final FileAccessTimeJournal fileAccessTimeJournal;
    private final TemporaryFileProvider temporaryFileProvider;

    @Inject
    public DirectoryBuildCacheServiceFactory(CacheRepository cacheRepository, GlobalScopedCache globalScopedCache, PathToFileResolver resolver, DirectoryBuildCacheFileStoreFactory fileStoreFactory,
                                             CleanupActionFactory cleanupActionFactory, FileAccessTimeJournal fileAccessTimeJournal, TemporaryFileProvider temporaryFileProvider) {
        this.cacheRepository = cacheRepository;
        this.globalScopedCache = globalScopedCache;
        this.resolver = resolver;
        this.fileStoreFactory = fileStoreFactory;
        this.cleanupActionFactory = cleanupActionFactory;
        this.fileAccessTimeJournal = fileAccessTimeJournal;
        this.temporaryFileProvider = temporaryFileProvider;
    }

    @Override
    public BuildCacheService createBuildCacheService(DirectoryBuildCache configuration, Describer describer) {
        Object cacheDirectory = configuration.getDirectory();
        File target;
        if (cacheDirectory != null) {
            target = resolver.resolve(cacheDirectory);
        } else {
            target = globalScopedCache.baseDirForCrossVersionCache(BUILD_CACHE_KEY);
        }
        checkDirectory(target);

        int removeUnusedEntriesAfterDays = configuration.getRemoveUnusedEntriesAfterDays();
        describer.type(DIRECTORY_BUILD_CACHE_TYPE).
            config("location", target.getAbsolutePath()).
            config("removeUnusedEntriesAfter", String.valueOf(removeUnusedEntriesAfterDays) + " days");

        PathKeyFileStore fileStore = fileStoreFactory.createFileStore(target);
        PersistentCache persistentCache = cacheRepository
            .cache(target)
            .withCleanup(cleanupActionFactory.create(new LeastRecentlyUsedCacheCleanup(new SingleDepthFilesFinder(FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP), fileAccessTimeJournal, removeUnusedEntriesAfterDays)))
            .withDisplayName("Build cache")
            .withLockOptions(mode(OnDemand))
            .withCrossVersionCache(CacheBuilder.LockTarget.DefaultTarget)
            .open();
        BuildCacheTempFileStore tempFileStore = new DefaultBuildCacheTempFileStore(temporaryFileProvider);
        FileAccessTracker fileAccessTracker = new SingleDepthFileAccessTracker(fileAccessTimeJournal, target, FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP);

        return new DirectoryBuildCacheService(fileStore, persistentCache, tempFileStore, fileAccessTracker, FAILED_READ_SUFFIX);
    }

    private static void checkDirectory(File directory) {
        if (directory.exists()) {
            if (!directory.isDirectory()) {
                throw new IllegalArgumentException(String.format("Cache directory %s must be a directory", directory));
            }
            if (!directory.canRead()) {
                throw new IllegalArgumentException(String.format("Cache directory %s must be readable", directory));
            }
            if (!directory.canWrite()) {
                throw new IllegalArgumentException(String.format("Cache directory %s must be writable", directory));
            }
        } else {
            if (!directory.mkdirs()) {
                throw new UncheckedIOException(String.format("Could not create cache directory: %s", directory));
            }
        }
    }
}
