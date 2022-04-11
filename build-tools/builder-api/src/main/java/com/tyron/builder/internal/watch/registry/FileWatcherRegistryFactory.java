package com.tyron.builder.internal.watch.registry;

public interface FileWatcherRegistryFactory {
    /**
     * Create the file watcher registry.
     */
    FileWatcherRegistry createFileWatcherRegistry(FileWatcherRegistry.ChangeHandler handler);
}

