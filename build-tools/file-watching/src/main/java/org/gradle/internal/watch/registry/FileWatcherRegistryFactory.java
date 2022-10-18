package org.gradle.internal.watch.registry;

public interface FileWatcherRegistryFactory {
    /**
     * Create the file watcher registry.
     */
    FileWatcherRegistry createFileWatcherRegistry(FileWatcherRegistry.ChangeHandler handler);
}

