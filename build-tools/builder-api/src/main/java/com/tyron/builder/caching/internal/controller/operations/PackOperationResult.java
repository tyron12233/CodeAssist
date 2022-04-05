package com.tyron.builder.caching.internal.controller.operations;

public class PackOperationResult implements BuildCacheArchivePackBuildOperationType.Result {

    private final long archiveEntryCount;
    private final long archiveSize;

    public PackOperationResult(long archiveEntryCount, long archiveSize) {
        this.archiveEntryCount = archiveEntryCount;
        this.archiveSize = archiveSize;
    }

    @Override
    public long getArchiveSize() {
        return archiveSize;
    }

    @Override
    public long getArchiveEntryCount() {
        return archiveEntryCount;
    }
}