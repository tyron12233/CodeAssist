package org.gradle.internal.watch.vfs;

public interface FileSystemWatchingStatistics {
    int getNumberOfReceivedEvents();
    int getNumberOfWatchedHierarchies();

    int getRetainedRegularFiles();
    int getRetainedDirectories();
    int getRetainedMissingFiles();
}