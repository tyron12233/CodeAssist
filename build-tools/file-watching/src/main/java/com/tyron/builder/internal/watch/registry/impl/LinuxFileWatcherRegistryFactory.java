package com.tyron.builder.internal.watch.registry.impl;

import static net.rubygrapefruit.platform.internal.jni.LinuxFileEventFunctions.*;

import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.snapshot.SnapshotHierarchy;
import com.tyron.builder.internal.watch.registry.FileWatcherProbeRegistry;
import com.tyron.builder.internal.watch.registry.FileWatcherUpdater;

import net.rubygrapefruit.platform.NativeIntegrationUnavailableException;
import net.rubygrapefruit.platform.file.FileWatchEvent;
import net.rubygrapefruit.platform.file.FileWatcher;
import net.rubygrapefruit.platform.internal.jni.AbstractFileEventFunctions;
import net.rubygrapefruit.platform.internal.jni.InsufficientResourcesForWatchingException;

import org.apache.commons.vfs2.FileChangeEvent;
import org.apache.commons.vfs2.FileListener;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;
import org.apache.commons.vfs2.impl.DefaultFileMonitor;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class LinuxFileWatcherRegistryFactory extends AbstractFileWatcherRegistryFactory<LinuxFileWatcherRegistryFactory.LinuxFileEventFunctions, LinuxFileWatcherRegistryFactory.LinuxFileWatcher> {

    private final BlockingQueue<FileWatchEvent> queue = new ArrayBlockingQueue<>(10);

    protected static class LinuxFileWatcher implements FileWatcher {

        private final FileSystemManager manager;
        private DefaultFileMonitor fileMonitor;
        private final FileListener fileListener;

        public LinuxFileWatcher(FileListener fileListener) {
            try {
                manager = VFS.getManager();
            } catch (FileSystemException e) {
                throw new RuntimeException(e);
            }
            this.fileListener = fileListener;
        }

        @Override
        public void startWatching(Collection<File> collection) throws InsufficientResourcesForWatchingException {
            if (fileMonitor != null) {
                return;
            }

            List<FileObject> files = resolveFileObjects(collection);
            fileMonitor = new DefaultFileMonitor(fileListener);
            fileMonitor.setRecursive(true);
            files.forEach(fileMonitor::addFile);
            fileMonitor.start();
        }

        @Override
        public boolean stopWatching(Collection<File> collection) {
            if (fileMonitor == null) {
                return false;
            }
            List<FileObject> fileObjects = resolveFileObjects(collection);
            fileObjects.forEach(fileMonitor::removeFile);
            return true;
        }

        private List<FileObject> resolveFileObjects(Collection<File> collection) {
            return collection.stream()
                    .map(File::toString)
                    .map(path -> {
                        try {
                            return manager.resolveFile(path);
                        } catch (FileSystemException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        @Override
        public void shutdown() {
            if (fileMonitor != null) {
                fileMonitor.stop();
            }
        }

        @Override
        public boolean awaitTermination(long l, TimeUnit timeUnit) throws InterruptedException {
            shutdown();
            return true;
        }

        public Collection<File> stopWatchingMovedPaths(Collection<File> directoriesToCheck) {
            return Collections.emptyList();
        }
    }

    protected static class LinuxFileEventFunctions extends AbstractFileEventFunctions<LinuxFileWatcher> {

        @Override
        public void invalidateLogLevelCache() {

        }

        @Override
        public AbstractWatcherBuilder<LinuxFileWatcher> newWatcher(BlockingQueue<FileWatchEvent> blockingQueue) {
            return new AbstractWatcherBuilder<LinuxFileWatcher>(blockingQueue) {
                @Override
                protected Object startWatcher(NativeFileWatcherCallback nativeFileWatcherCallback) {
                    return new Object();
                }

                @Override
                protected LinuxFileWatcher createWatcher(Object o,
                                                         long l,
                                                         TimeUnit timeUnit,
                                                         NativeFileWatcherCallback nativeFileWatcherCallback) throws InterruptedException {
                    return new LinuxFileWatcher(new FileListener() {
                        @Override
                        public void fileCreated(FileChangeEvent event) throws Exception {
                            FileObject fileObject = event.getFileObject();
                            nativeFileWatcherCallback.reportChangeEvent(FileWatchEvent.ChangeType.CREATED.ordinal(), fileObject.getPath().toString());
                        }

                        @Override
                        public void fileDeleted(FileChangeEvent event) throws Exception {
                            FileObject fileObject = event.getFileObject();
                            nativeFileWatcherCallback.reportChangeEvent(
                                    FileWatchEvent.ChangeType.REMOVED.ordinal(),
                                    fileObject.getPath().toString()
                            );
                        }

                        @Override
                        public void fileChanged(FileChangeEvent event) throws Exception {
                            FileObject fileObject = event.getFileObject();
                            nativeFileWatcherCallback.reportChangeEvent(
                                    FileWatchEvent.ChangeType.MODIFIED.ordinal(),
                                    fileObject.getPath().toString()
                            );
                        }
                    });
                }
            };
        }
    }

    private int getChangeTypeIndex(FileWatchEvent.ChangeType event) {
        return event.ordinal();
    }

    public LinuxFileWatcherRegistryFactory(Predicate<String> watchFilter) throws NativeIntegrationUnavailableException {
        super(new LinuxFileEventFunctions(), watchFilter);
    }

    @Override
    protected LinuxFileWatcher createFileWatcher(BlockingQueue<FileWatchEvent> fileEvents) throws InterruptedException {
        AbstractWatcherBuilder<LinuxFileWatcher> linuxFileWatcherAbstractWatcherBuilder =
                this.fileEventFunctions.newWatcher(fileEvents);
        return linuxFileWatcherAbstractWatcherBuilder.start();
    }

    @Override
    protected FileWatcherUpdater createFileWatcherUpdater(LinuxFileWatcher watcher, FileWatcherProbeRegistry probeRegistry, WatchableHierarchies watchableHierarchies) {
        return new NonHierarchicalFileWatcherUpdater(watcher, probeRegistry, watchableHierarchies, new LinuxMovedDirectoryHandler(watcher, watchableHierarchies));
    }

    private static class LinuxMovedDirectoryHandler implements AbstractFileWatcherUpdater.MovedDirectoryHandler {
        private final LinuxFileWatcher watcher;
        private final WatchableHierarchies watchableHierarchies;

        public LinuxMovedDirectoryHandler(LinuxFileWatcher watcher, WatchableHierarchies watchableHierarchies) {
            this.watcher = watcher;
            this.watchableHierarchies = watchableHierarchies;
        }

        @Override
        public Collection<File> stopWatchingMovedDirectories(SnapshotHierarchy vfsRoot) {
            Collection<File> directoriesToCheck = vfsRoot.rootSnapshots()
                    .filter(snapshot -> snapshot.getType() != FileType.Missing)
                    .filter(watchableHierarchies::shouldWatch)
                    .map(snapshot -> {
                        switch (snapshot.getType()) {
                            case RegularFile:
                                return new File(snapshot.getAbsolutePath()).getParentFile();
                            case Directory:
                                return new File(snapshot.getAbsolutePath());
                            default:
                                throw new IllegalArgumentException("Unexpected file type:" + snapshot.getType());
                        }
                    })
                    .collect(Collectors.toList());
            return watcher.stopWatchingMovedPaths(directoriesToCheck);
        }
    }
}

