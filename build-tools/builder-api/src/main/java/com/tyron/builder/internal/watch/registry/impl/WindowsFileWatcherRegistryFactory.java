package com.tyron.builder.internal.watch.registry.impl;

import static com.tyron.builder.internal.watch.registry.impl.HierarchicalFileWatcherUpdater.FileSystemLocationToWatchValidator.NO_VALIDATION;
import static net.rubygrapefruit.platform.internal.jni.WindowsFileEventFunctions.*;

import com.tyron.builder.internal.watch.registry.FileWatcherProbeRegistry;
import com.tyron.builder.internal.watch.registry.FileWatcherUpdater;

import net.rubygrapefruit.platform.NativeIntegrationUnavailableException;
import net.rubygrapefruit.platform.file.FileEvents;
import net.rubygrapefruit.platform.file.FileWatchEvent;
import net.rubygrapefruit.platform.internal.jni.WindowsFileEventFunctions;

import java.util.concurrent.BlockingQueue;
import java.util.function.Predicate;


public class WindowsFileWatcherRegistryFactory extends AbstractFileWatcherRegistryFactory<WindowsFileEventFunctions, WindowsFileWatcher> {

    // 64 kB is the limit for SMB drives
    // See https://docs.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-readdirectorychangesw#remarks:~:text=ERROR_INVALID_PARAMETER
    private static final int BUFFER_SIZE = 64 * 1024;

    public WindowsFileWatcherRegistryFactory(
            Predicate<String> watchFilter
    ) throws NativeIntegrationUnavailableException {
        super(FileEvents.get(WindowsFileEventFunctions.class), watchFilter);
    }

    @Override
    protected WindowsFileWatcher createFileWatcher(BlockingQueue<FileWatchEvent> fileEvents) throws InterruptedException {
        return fileEventFunctions.newWatcher(fileEvents)
                .withBufferSize(BUFFER_SIZE)
                .start();
    }

    @Override
    protected FileWatcherUpdater createFileWatcherUpdater(
            WindowsFileWatcher watcher,
            FileWatcherProbeRegistry probeRegistry,
            WatchableHierarchies watchableHierarchies
    ) {
        return new HierarchicalFileWatcherUpdater(watcher, NO_VALIDATION, probeRegistry, watchableHierarchies, root -> watcher.stopWatchingMovedPaths());
    }
}
