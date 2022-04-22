package com.tyron.builder.internal.watch.vfs.impl;

import static com.tyron.builder.internal.snapshot.SnapshotVisitResult.CONTINUE;

import com.google.common.collect.EnumMultiset;
import com.google.common.collect.Multiset;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.snapshot.SnapshotHierarchy;
import com.tyron.builder.internal.watch.registry.FileWatcherRegistry;
import com.tyron.builder.internal.watch.vfs.FileSystemWatchingStatistics;

public class DefaultFileSystemWatchingStatistics implements FileSystemWatchingStatistics {
    private final FileWatcherRegistry.FileWatchingStatistics fileWatchingStatistics;
    private final VirtualFileSystemStatistics vfsStatistics;

    public DefaultFileSystemWatchingStatistics(
            FileWatcherRegistry.FileWatchingStatistics fileWatchingStatistics,
            SnapshotHierarchy vfsRoot
    ) {
        this.fileWatchingStatistics = fileWatchingStatistics;
        this.vfsStatistics = getStatistics(vfsRoot);
    }

    @Override
    public int getNumberOfReceivedEvents() {
        return fileWatchingStatistics.getNumberOfReceivedEvents();
    }

    @Override
    public int getNumberOfWatchedHierarchies() {
        return fileWatchingStatistics.getNumberOfWatchedHierarchies();
    }

    @Override
    public int getRetainedRegularFiles() {
        return vfsStatistics.getRetained(FileType.RegularFile);
    }

    @Override
    public int getRetainedDirectories() {
        return vfsStatistics.getRetained(FileType.Directory);
    }

    @Override
    public int getRetainedMissingFiles() {
        return vfsStatistics.getRetained(FileType.Missing);
    }

    private static VirtualFileSystemStatistics getStatistics(SnapshotHierarchy root) {
        EnumMultiset<FileType> retained = EnumMultiset.create(FileType.class);
        root.rootSnapshots()
                .forEach(snapshot -> snapshot.accept(entrySnapshot -> {
                    retained.add(entrySnapshot.getType());
                    return CONTINUE;
                }));
        return new VirtualFileSystemStatistics(retained);
    }

    private static class VirtualFileSystemStatistics {
        private final Multiset<FileType> retained;

        public VirtualFileSystemStatistics(Multiset<FileType> retained) {
            this.retained = retained;
        }

        public int getRetained(FileType fileType) {
            return retained.count(fileType);
        }
    }
}
