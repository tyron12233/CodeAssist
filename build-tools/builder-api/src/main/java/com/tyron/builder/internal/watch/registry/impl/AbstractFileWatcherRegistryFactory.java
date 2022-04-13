package com.tyron.builder.internal.watch.registry.impl;

import com.tyron.builder.internal.watch.registry.FileWatcherProbeRegistry;
import com.tyron.builder.internal.watch.registry.FileWatcherRegistry;
import com.tyron.builder.internal.watch.registry.FileWatcherRegistryFactory;
import com.tyron.builder.internal.watch.registry.FileWatcherUpdater;

import net.rubygrapefruit.platform.file.FileWatchEvent;
import net.rubygrapefruit.platform.file.FileWatcher;
import net.rubygrapefruit.platform.internal.jni.AbstractFileEventFunctions;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Predicate;

public abstract class AbstractFileWatcherRegistryFactory<T extends AbstractFileEventFunctions<W>, W extends FileWatcher> implements FileWatcherRegistryFactory {
    private static final int FILE_EVENT_QUEUE_SIZE = 4096;

    protected final T fileEventFunctions;
    private final Predicate<String> watchFilter;

    public AbstractFileWatcherRegistryFactory(
            T fileEventFunctions,
            Predicate<String> watchFilter
    ) {
        this.fileEventFunctions = fileEventFunctions;
        this.watchFilter = watchFilter;
    }

    @Override
    public FileWatcherRegistry createFileWatcherRegistry(FileWatcherRegistry.ChangeHandler handler) {
        BlockingQueue<FileWatchEvent> fileEvents = new ArrayBlockingQueue<>(FILE_EVENT_QUEUE_SIZE);
        try {
            // TODO How can we avoid hard-coding ".gradle" here?
            FileWatcherProbeRegistry probeRegistry = new DefaultFileWatcherProbeRegistry(buildDir ->
                    new File(new File(buildDir, ".gradle"), "file-system.probe"));
            W watcher = createFileWatcher(fileEvents);
            WatchableHierarchies watchableHierarchies = new WatchableHierarchies(probeRegistry, watchFilter);
            FileWatcherUpdater fileWatcherUpdater = createFileWatcherUpdater(watcher, probeRegistry, watchableHierarchies);
            return new DefaultFileWatcherRegistry(
                    fileEventFunctions,
                    watcher,
                    handler,
                    fileWatcherUpdater,
                    fileEvents
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    protected abstract W createFileWatcher(BlockingQueue<FileWatchEvent> fileEvents) throws InterruptedException;

    protected abstract FileWatcherUpdater createFileWatcherUpdater(W watcher, FileWatcherProbeRegistry probeRegistry, WatchableHierarchies watchableHierarchies);
}
