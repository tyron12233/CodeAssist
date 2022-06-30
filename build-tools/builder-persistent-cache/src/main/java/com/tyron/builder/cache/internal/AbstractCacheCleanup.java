package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.CleanableStore;
import com.tyron.builder.cache.CleanupAction;
import com.tyron.builder.cache.CleanupProgressMonitor;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public abstract class AbstractCacheCleanup implements CleanupAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractCacheCleanup.class);

    private final FilesFinder eligibleFilesFinder;

    public AbstractCacheCleanup(FilesFinder eligibleFilesFinder) {
        this.eligibleFilesFinder = eligibleFilesFinder;
    }

    @Override
    public void clean(CleanableStore cleanableStore, CleanupProgressMonitor progressMonitor) {
        int filesDeleted = 0;
        for (File file : findEligibleFiles(cleanableStore)) {
            if (shouldDelete(file)) {
                progressMonitor.incrementDeleted();
                if (FileUtils.deleteQuietly(file)) {
                    handleDeletion(file);
                    filesDeleted += 1 + deleteEmptyParentDirectories(cleanableStore.getBaseDir(), file.getParentFile());
                }
            } else {
                progressMonitor.incrementSkipped();
            }
        }
        LOGGER.info("{} cleanup deleted {} files/directories.", cleanableStore.getDisplayName(), filesDeleted);
    }

    protected int deleteEmptyParentDirectories(File baseDir, File dir) {
        if (dir.equals(baseDir)) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files != null && files.length == 0 && dir.delete()) {
            handleDeletion(dir);
            return 1 + deleteEmptyParentDirectories(baseDir, dir.getParentFile());
        }
        return 0;
    }

    protected abstract boolean shouldDelete(File file);

    protected abstract void handleDeletion(File file);

    private Iterable<File> findEligibleFiles(CleanableStore cleanableStore) {
        return eligibleFilesFinder.find(cleanableStore.getBaseDir(), new NonReservedFileFilter(cleanableStore.getReservedCacheFiles()));
    }

}
