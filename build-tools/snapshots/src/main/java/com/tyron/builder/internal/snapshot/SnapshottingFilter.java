package com.tyron.builder.internal.snapshot;

import java.nio.file.Path;

public interface SnapshottingFilter {
    SnapshottingFilter EMPTY = new SnapshottingFilter() {
        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public FileSystemSnapshotPredicate getAsSnapshotPredicate() {
            return (location, relativePath) -> true;
        }

        @Override
        public DirectoryWalkerPredicate getAsDirectoryWalkerPredicate() {
            return (path, name, isDirectory, relativePath) -> true;
        }
    };

    boolean isEmpty();
    FileSystemSnapshotPredicate getAsSnapshotPredicate();
    DirectoryWalkerPredicate getAsDirectoryWalkerPredicate();

    interface DirectoryWalkerPredicate {
        boolean test(Path path, String name, boolean isDirectory, Iterable<String> relativePath);
    }

    interface FileSystemSnapshotPredicate {
        boolean test(FileSystemLocationSnapshot fileSystemLocation, Iterable<String> relativePath);
    }
}