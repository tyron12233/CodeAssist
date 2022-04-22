package com.tyron.builder.internal.watch.registry.impl;

import static com.tyron.builder.internal.watch.registry.FileWatcherRegistry.Type.CREATED;
import static com.tyron.builder.internal.watch.registry.FileWatcherRegistry.Type.INVALIDATED;
import static com.tyron.builder.internal.watch.registry.FileWatcherRegistry.Type.MODIFIED;
import static com.tyron.builder.internal.watch.registry.FileWatcherRegistry.Type.OVERFLOW;
import static com.tyron.builder.internal.watch.registry.FileWatcherRegistry.Type.REMOVED;

import static net.rubygrapefruit.platform.file.FileWatchEvent.*;
import static net.rubygrapefruit.platform.file.FileWatchEvent.ChangeType.*;

import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.SnapshotHierarchy;
import com.tyron.builder.internal.watch.registry.FileWatcherRegistry;
import com.tyron.builder.internal.watch.registry.FileWatcherUpdater;
import com.tyron.builder.internal.watch.registry.WatchMode;

import net.rubygrapefruit.platform.file.FileWatchEvent;
import net.rubygrapefruit.platform.file.FileWatcher;
import net.rubygrapefruit.platform.internal.jni.AbstractFileEventFunctions;
import net.rubygrapefruit.platform.internal.jni.NativeLogger;

import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;


public class DefaultFileWatcherRegistry implements FileWatcherRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultFileWatcherRegistry.class);

    private final AbstractFileEventFunctions<?> fileEventFunctions;
    private final FileWatcher watcher;
    private final BlockingQueue<FileWatchEvent> fileEvents;
    private final Thread eventConsumerThread;
    private final FileWatcherUpdater fileWatcherUpdater;

    private volatile MutableFileWatchingStatistics fileWatchingStatistics = new MutableFileWatchingStatistics();
    private volatile boolean consumeEvents = true;
    private volatile boolean stopping = false;

    public DefaultFileWatcherRegistry(
            AbstractFileEventFunctions<?> fileEventFunctions,
            FileWatcher watcher,
            ChangeHandler handler,
            FileWatcherUpdater fileWatcherUpdater,
            BlockingQueue<FileWatchEvent> fileEvents
    ) {
        this.fileEventFunctions = fileEventFunctions;
        this.watcher = watcher;
        this.fileEvents = fileEvents;
        this.fileWatcherUpdater = fileWatcherUpdater;
        this.eventConsumerThread = createAndStartEventConsumerThread(handler);
    }

    private Thread createAndStartEventConsumerThread(ChangeHandler handler) {
        Thread thread = new Thread(() -> {
            LOGGER.debug("Started listening to file system change events");
            try {
                while (consumeEvents) {
                    FileWatchEvent nextEvent = fileEvents.take();
                    if (!stopping) {
                        nextEvent.handleEvent(new Handler() {
                            @Override
                            public void handleChangeEvent(ChangeType type, String absolutePath) {
                                fileWatchingStatistics.eventReceived();
                                fileWatcherUpdater.triggerWatchProbe(absolutePath);
                                handler.handleChange(convertType(type), Paths.get(absolutePath));
                            }

                            @Override
                            public void handleUnknownEvent(String absolutePath) {
                                LOGGER.error("Received unknown event for {}", absolutePath);
                                fileWatchingStatistics.unknownEventEncountered();
                                handler.stopWatchingAfterError();
                            }

                            @Override
                            public void handleOverflow(OverflowType type, @Nullable String absolutePath) {
                                if (absolutePath == null) {
                                    LOGGER.info("Overflow detected (type: {}), invalidating all watched files", type);
                                    fileWatcherUpdater.getWatchedFiles().visitRoots(watchedRoot ->
                                            handler.handleChange(OVERFLOW, Paths.get(watchedRoot)));
                                } else {
                                    LOGGER.info("Overflow detected (type: {}) for watched path '{}', invalidating", type, absolutePath);
                                    handler.handleChange(OVERFLOW, Paths.get(absolutePath));
                                }
                            }

                            @Override
                            public void handleFailure(Throwable failure) {
                                LOGGER.error("Error while receiving file changes", failure);
                                fileWatchingStatistics.errorWhileReceivingFileChanges(failure);
                                handler.stopWatchingAfterError();
                            }

                            @Override
                            public void handleTerminated() {
                                consumeEvents = false;
                            }
                        });
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // stop thread
            }
            LOGGER.debug("Finished listening to file system change events");
        });
        thread.setDaemon(true);
        thread.setName("File watcher consumer");
        thread.setUncaughtExceptionHandler((failedThread, exception) -> {
            LOGGER.error("File system event consumer thread stopped due to exception", exception);
            handler.stopWatchingAfterError();
        });
        thread.start();
        return thread;
    }

    @Override
    public boolean isWatchingAnyLocations() {
        return !fileWatcherUpdater.getWatchedFiles().isEmpty();
    }

    @Override
    public void registerWatchableHierarchy(File watchableHierarchy, SnapshotHierarchy root) {
        fileWatcherUpdater.registerWatchableHierarchy(watchableHierarchy, root);
    }

    @Override
    public void virtualFileSystemContentsChanged(Collection<FileSystemLocationSnapshot> removedSnapshots, Collection<FileSystemLocationSnapshot> addedSnapshots, SnapshotHierarchy root) {
        fileWatcherUpdater.virtualFileSystemContentsChanged(removedSnapshots, addedSnapshots, root);
    }

    @Override
    public SnapshotHierarchy updateVfsOnBuildStarted(SnapshotHierarchy root, WatchMode watchMode, List<File> unsupportedFileSystems) {
        return fileWatcherUpdater.updateVfsOnBuildStarted(root, watchMode, unsupportedFileSystems);
    }

    @Override
    public SnapshotHierarchy updateVfsOnBuildFinished(SnapshotHierarchy root, WatchMode watchMode, int maximumNumberOfWatchedHierarchies, List<File> unsupportedFileSystems) {
        return fileWatcherUpdater.updateVfsOnBuildFinished(root, watchMode, maximumNumberOfWatchedHierarchies, unsupportedFileSystems);
    }

    private static Type convertType(ChangeType type) {
        switch (type) {
            case CREATED:
                return CREATED;
            case MODIFIED:
                return MODIFIED;
            case REMOVED:
                return REMOVED;
            case INVALIDATED:
                return INVALIDATED;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public FileWatchingStatistics getAndResetStatistics() {
        MutableFileWatchingStatistics currentStatistics = fileWatchingStatistics;
        fileWatchingStatistics = new MutableFileWatchingStatistics();
        AtomicInteger numberOfWatchedHierarchies = new AtomicInteger(0);
        fileWatcherUpdater.getWatchedFiles().visitRoots(root -> numberOfWatchedHierarchies.incrementAndGet());
        return new FileWatchingStatistics() {
            @Override
            public Optional<Throwable> getErrorWhileReceivingFileChanges() {
                return currentStatistics.getErrorWhileReceivingFileChanges();
            }

            @Override
            public boolean isUnknownEventEncountered() {
                return currentStatistics.isUnknownEventEncountered();
            }

            @Override
            public int getNumberOfReceivedEvents() {
                return currentStatistics.getNumberOfReceivedEvents();
            }

            @Override
            public int getNumberOfWatchedHierarchies() {
                return numberOfWatchedHierarchies.get();
            }
        };
    }

    @Override
    public void setDebugLoggingEnabled(boolean debugLoggingEnabled) {
        java.util.logging.Logger.getLogger(NativeLogger.class.getName()).setLevel(debugLoggingEnabled
                ? Level.FINEST
                : Level.INFO
        );
        fileEventFunctions.invalidateLogLevelCache();
    }

    @Override
    public void close() throws IOException {
        stopping = true;
        try {
            watcher.shutdown();
            if (!watcher.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Watcher did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Awaiting termination of watcher was interrupted");
        } finally {
            consumeEvents = false;
            eventConsumerThread.interrupt();
        }
    }

    private static class MutableFileWatchingStatistics {
        private boolean unknownEventEncountered;
        private int numberOfReceivedEvents;
        private Throwable errorWhileReceivingFileChanges;

        public Optional<Throwable> getErrorWhileReceivingFileChanges() {
            return Optional.ofNullable(errorWhileReceivingFileChanges);
        }

        public boolean isUnknownEventEncountered() {
            return unknownEventEncountered;
        }

        public int getNumberOfReceivedEvents() {
            return numberOfReceivedEvents;
        }

        public void eventReceived() {
            numberOfReceivedEvents++;
        }

        public void errorWhileReceivingFileChanges(Throwable error) {
            if (errorWhileReceivingFileChanges != null) {
                errorWhileReceivingFileChanges = error;
            }
        }

        public void unknownEventEncountered() {
            unknownEventEncountered = true;
        }
    }
}
