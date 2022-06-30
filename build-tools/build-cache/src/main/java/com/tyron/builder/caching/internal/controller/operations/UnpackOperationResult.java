package com.tyron.builder.caching.internal.controller.operations;

public class UnpackOperationResult implements BuildCacheArchiveUnpackBuildOperationType.Result {

    private final long archiveEntryCount;

    public UnpackOperationResult(long archiveEntryCount) {
        this.archiveEntryCount = archiveEntryCount;
    }

    @Override
    public long getArchiveEntryCount() {
        return archiveEntryCount;
    }

}