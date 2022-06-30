package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.CleanableStore;
import com.tyron.builder.cache.CleanupProgressMonitor;
import com.tyron.builder.internal.file.FileAccessTimeJournal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Deletes any cache entries not accessed within the specified number of days.
 */
public class LeastRecentlyUsedCacheCleanup extends AbstractCacheCleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger(LeastRecentlyUsedCacheCleanup.class);

    public static final long DEFAULT_MAX_AGE_IN_DAYS_FOR_RECREATABLE_CACHE_ENTRIES = 7;
    public static final long DEFAULT_MAX_AGE_IN_DAYS_FOR_EXTERNAL_CACHE_ENTRIES = 30;

    private final FileAccessTimeJournal journal;
    private final long minimumTimestamp;

    public LeastRecentlyUsedCacheCleanup(FilesFinder eligibleFilesFinder, FileAccessTimeJournal journal, long numberOfDays) {
        super(eligibleFilesFinder);
        this.journal = journal;
        this.minimumTimestamp = Math.max(0, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(numberOfDays));
    }

    @Override
    public void clean(CleanableStore cleanableStore, CleanupProgressMonitor progressMonitor) {
        LOGGER.info("{} removing files not accessed on or after {}.", cleanableStore.getDisplayName(), new Date(minimumTimestamp));
        super.clean(cleanableStore, progressMonitor);
    }

    @Override
    protected boolean shouldDelete(File file) {
        return journal.getLastAccessTime(file) < minimumTimestamp;
    }

    @Override
    protected void handleDeletion(File file) {
        journal.deleteLastAccessTime(file);
    }
}
