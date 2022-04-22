package com.tyron.builder.internal.watch.vfs;


import com.tyron.builder.internal.operations.BuildOperationType;

import javax.annotation.Nullable;

public interface BuildStartedFileSystemWatchingBuildOperationType extends BuildOperationType<BuildStartedFileSystemWatchingBuildOperationType.Details, BuildStartedFileSystemWatchingBuildOperationType.Result> {
    String DISPLAY_NAME = "Build started for file system watching";

    interface Details {
        Details INSTANCE = new Details() {};
    }

    interface Result {
        Result WATCHING_DISABLED = new Result() {
            @Override
            public boolean isWatchingEnabled() {
                return false;
            }

            @Override
            public boolean isStartedWatching() {
                return false;
            }

            @Override
            public FileSystemWatchingStatistics getStatistics() {
                return null;
            }
        };

        boolean isWatchingEnabled();

        boolean isStartedWatching();

        @Nullable
        FileSystemWatchingStatistics getStatistics();
    }
}
