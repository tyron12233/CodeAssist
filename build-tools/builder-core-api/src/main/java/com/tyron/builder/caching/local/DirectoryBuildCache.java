package com.tyron.builder.caching.local;


import com.tyron.builder.caching.configuration.AbstractBuildCache;

import org.jetbrains.annotations.Nullable;

/**
 * Configuration object for the local directory build cache.
 *
 * @since 3.5
 */
public class DirectoryBuildCache extends AbstractBuildCache {
    private Object directory;
    private int removeUnusedEntriesAfterDays = 7;

    /**
     * Returns the directory to use to store the build cache.
     */
    @Nullable
    public Object getDirectory() {
        return directory;
    }

    /**
     * Sets the directory to use to store the build cache.
     *
     * The directory is evaluated as per {@code Project.file(Object)}.
     */
    public void setDirectory(@Nullable Object directory) {
        this.directory = directory;
    }

    /**
     * Returns the number of days after unused entries are garbage collected. Defaults to 7 days.
     *
     * @since 4.6
     */
    public int getRemoveUnusedEntriesAfterDays() {
        return removeUnusedEntriesAfterDays;
    }

    /**
     * Sets the number of days after unused entries are garbage collected. Defaults to 7 days.
     *
     * Must be greater than 1.
     *
     * @since 4.6
     */
    public void setRemoveUnusedEntriesAfterDays(int removeUnusedEntriesAfterDays) {
        if (removeUnusedEntriesAfterDays < 1) {
            throw new IllegalArgumentException("Directory build cache needs to retain entries for at least a day.");
        }
        this.removeUnusedEntriesAfterDays = removeUnusedEntriesAfterDays;
    }
}