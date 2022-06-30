package com.tyron.builder.internal.watch.registry.impl;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multiset;
import com.tyron.builder.internal.snapshot.DirectorySnapshot;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot.FileSystemLocationSnapshotTransformer;
import com.tyron.builder.internal.snapshot.MissingFileSnapshot;
import com.tyron.builder.internal.snapshot.RegularFileSnapshot;
import com.tyron.builder.internal.snapshot.RootTrackingFileSystemSnapshotHierarchyVisitor;
import com.tyron.builder.internal.snapshot.SnapshotHierarchy;
import com.tyron.builder.internal.snapshot.SnapshotVisitResult;
import com.tyron.builder.internal.file.FileHierarchySet;
import com.tyron.builder.internal.watch.WatchingNotSupportedException;
import com.tyron.builder.internal.watch.registry.FileWatcherProbeRegistry;

import net.rubygrapefruit.platform.NativeException;
import net.rubygrapefruit.platform.file.FileWatcher;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class NonHierarchicalFileWatcherUpdater extends AbstractFileWatcherUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(NonHierarchicalFileWatcherUpdater.class);

    private final FileWatcher fileWatcher;
    private final Multiset<String> watchedDirectories = HashMultiset.create();
    private final Map<String, String> watchedDirectoryForSnapshot = new HashMap<>();
    private final Set<String> watchedWatchableHierarchies = new HashSet<>();

    public NonHierarchicalFileWatcherUpdater(
            FileWatcher fileWatcher,
            FileWatcherProbeRegistry probeRegistry,
            WatchableHierarchies watchableHierarchies,
            MovedDirectoryHandler movedDirectoryHandler
    ) {
        super(probeRegistry, watchableHierarchies, movedDirectoryHandler);
        this.fileWatcher = fileWatcher;
    }

    @Override
    protected boolean handleVirtualFileSystemContentsChanged(Collection<FileSystemLocationSnapshot> removedSnapshots, Collection<FileSystemLocationSnapshot> addedSnapshots, SnapshotHierarchy root) {
        Map<String, Integer> changedWatchedDirectories = new HashMap<>();

        removedSnapshots.stream()
                .filter(watchableHierarchies::shouldWatch)
                .forEach(snapshot -> {
                    String previousWatchedRoot = watchedDirectoryForSnapshot.remove(snapshot.getAbsolutePath());
                    decrement(previousWatchedRoot, changedWatchedDirectories);
                    snapshot.accept(new SubdirectoriesToWatchVisitor(path -> decrement(path, changedWatchedDirectories)));
                });
        addedSnapshots.stream()
                .filter(watchableHierarchies::shouldWatch)
                .forEach(snapshot -> {
                    File directoryToWatchForRoot = SnapshotWatchedDirectoryFinder.getDirectoryToWatch(snapshot);
                    String pathToWatchForRoot = directoryToWatchForRoot.getAbsolutePath();
                    if (!watchableHierarchies.isInWatchableHierarchy(pathToWatchForRoot)) {
                        return;
                    }
                    watchedDirectoryForSnapshot.put(snapshot.getAbsolutePath(), pathToWatchForRoot);
                    increment(pathToWatchForRoot, changedWatchedDirectories);
                    snapshot.accept(new SubdirectoriesToWatchVisitor(path -> increment(path, changedWatchedDirectories)));
                });
        if (changedWatchedDirectories.isEmpty()) {
            return false;
        }
        updateWatchedDirectories(changedWatchedDirectories);
        return true;
    }

    @Override
    protected void updateWatchesOnChangedWatchedFiles(FileHierarchySet newWatchedFiles) {
        // Most of the changes already happened in `handleVirtualFileSystemContentsChanged`.
        // Here we only need to update watches for the roots of the hierarchies.
        Map<String, Integer> changedWatchDirectories = new HashMap<>();
        watchedWatchableHierarchies.forEach(absolutePath -> decrement(absolutePath, changedWatchDirectories));
        watchedWatchableHierarchies.clear();
        newWatchedFiles.visitRoots(absolutePath -> {
            watchedWatchableHierarchies.add(absolutePath);
            increment(absolutePath, changedWatchDirectories);
        });
        if (!changedWatchDirectories.isEmpty()) {
            updateWatchedDirectories(changedWatchDirectories);
        }
    }

    @Override
    protected WatchableHierarchies.Invalidator createInvalidator() {
        return (location, currentRoot) -> {
            SnapshotCollectingDiffListener diffListener = new SnapshotCollectingDiffListener();
            SnapshotHierarchy invalidatedRoot = currentRoot.invalidate(location, diffListener);
            diffListener.publishSnapshotDiff((removedSnapshots, addedSnapshots) -> virtualFileSystemContentsChanged(removedSnapshots, addedSnapshots, invalidatedRoot));
            return invalidatedRoot;
        };
    }

    @Override
    protected void startWatchingProbeDirectory(File probeDirectory) {
        updateWatchedDirectories(ImmutableMap.of(probeDirectory.getAbsolutePath(), 1));
    }

    @Override
    protected void stopWatchingProbeDirectory(File probeDirectory) {
        updateWatchedDirectories(ImmutableMap.of(probeDirectory.getAbsolutePath(), -1));
    }

    private void updateWatchedDirectories(Map<String, Integer> changedWatchDirectories) {
        Set<File> directoriesToStopWatching = new HashSet<>();
        Set<File> directoriesToStartWatching = new HashSet<>();
        changedWatchDirectories.forEach((absolutePath, value) -> {
            int count = value;
            if (count < 0) {
                int toRemove = -count;
                int contained = watchedDirectories.remove(absolutePath, toRemove);
                if (contained <= toRemove) {
                    directoriesToStopWatching.add(new File(absolutePath));
                }
            } else if (count > 0) {
                int contained = watchedDirectories.add(absolutePath, count);
                if (contained == 0) {
                    directoriesToStartWatching.add(new File(absolutePath));
                }
            }
        });

        LOGGER.info("Watching {} directories to track changes", watchedDirectories.entrySet().size());

        try {
            if (!directoriesToStopWatching.isEmpty()) {
                if (!fileWatcher.stopWatching(directoriesToStopWatching)) {
                    LOGGER.debug("Couldn't stop watching directories: {}", directoriesToStopWatching);
                }
            }
            if (!directoriesToStartWatching.isEmpty()) {
                fileWatcher.startWatching(directoriesToStartWatching);
            }
        } catch (NativeException e) {
            if (e.getMessage().contains("Already watching path: ")) {
                throw new WatchingNotSupportedException("Unable to watch same file twice via different paths: " + e.getMessage(), e);
            }
            throw e;
        }
    }

    private static void decrement(String path, Map<String, Integer> changedWatchedDirectories) {
        changedWatchedDirectories.compute(path, (key, value) -> zeroToNull(nullToZero(value) - 1));
    }

    private static void increment(String path, Map<String, Integer> changedWatchedDirectories) {
        changedWatchedDirectories.compute(path, (key, value) -> zeroToNull(nullToZero(value) + 1));
    }

    @Nullable
    private static Integer zeroToNull(int value) {
        return value == 0 ? null : value;
    }

    private static int nullToZero(@Nullable Integer value) {
        return value == null ? 0 : value;
    }

    private class SubdirectoriesToWatchVisitor extends RootTrackingFileSystemSnapshotHierarchyVisitor {
        private final Consumer<String> subDirectoryToWatchConsumer;

        public SubdirectoriesToWatchVisitor(Consumer<String> subDirectoryToWatchConsumer) {
            this.subDirectoryToWatchConsumer = subDirectoryToWatchConsumer;
        }

        @Override
        public SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot, boolean isRoot) {
            if (isRoot) {
                return SnapshotVisitResult.CONTINUE;
            }
            return snapshot.accept(new FileSystemLocationSnapshotTransformer<SnapshotVisitResult>() {
                @Override
                public SnapshotVisitResult visitDirectory(DirectorySnapshot directorySnapshot) {
                    if (watchableHierarchies.ignoredForWatching(directorySnapshot)) {
                        return SnapshotVisitResult.SKIP_SUBTREE;
                    } else {
                        subDirectoryToWatchConsumer.accept(directorySnapshot.getAbsolutePath());
                        return SnapshotVisitResult.CONTINUE;
                    }
                }

                @Override
                public SnapshotVisitResult visitRegularFile(RegularFileSnapshot fileSnapshot) {
                    return SnapshotVisitResult.CONTINUE;
                }

                @Override
                public SnapshotVisitResult visitMissing(MissingFileSnapshot missingSnapshot) {
                    return SnapshotVisitResult.CONTINUE;
                }
            });
        }
    }
}

