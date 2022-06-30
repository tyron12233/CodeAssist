package com.tyron.builder.internal.watch.vfs;


import com.tyron.builder.internal.operations.BuildOperationType;

import javax.annotation.Nullable;

public interface BuildFinishedFileSystemWatchingBuildOperationType extends BuildOperationType<BuildFinishedFileSystemWatchingBuildOperationType.Details, BuildFinishedFileSystemWatchingBuildOperationType.Result> {
    String DISPLAY_NAME = "Build finished for file system watching";

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
            public boolean isStoppedWatchingDuringTheBuild() {
                return false;
            }

            @Override
            public boolean isStateInvalidatedAtStartOfBuild() {
                return false;
            }

            @Override
            public FileSystemWatchingStatistics getStatistics() {
                return null;
            }
        };

        boolean isWatchingEnabled();

        boolean isStoppedWatchingDuringTheBuild();

        boolean isStateInvalidatedAtStartOfBuild();

        @Nullable
        FileSystemWatchingStatistics getStatistics();
    }
}
