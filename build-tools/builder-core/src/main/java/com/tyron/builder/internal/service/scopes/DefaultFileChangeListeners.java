package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.internal.event.AnonymousListenerBroadcast;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.watch.registry.FileWatcherRegistry;
import com.tyron.builder.internal.watch.vfs.FileChangeListener;
import com.tyron.builder.internal.watch.vfs.FileChangeListeners;

import java.nio.file.Path;

public class DefaultFileChangeListeners implements FileChangeListeners {
    private final AnonymousListenerBroadcast<FileChangeListener> broadcaster;

    public DefaultFileChangeListeners(ListenerManager listenerManager) {
        this.broadcaster = listenerManager.createAnonymousBroadcaster(FileChangeListener.class);
    }

    @Override
    public void addListener(FileChangeListener listener) {
        broadcaster.add(listener);
    }

    @Override
    public void removeListener(FileChangeListener listener) {
        broadcaster.remove(listener);
    }

    @Override
    public void broadcastChange(FileWatcherRegistry.Type type, Path path) {
        broadcaster.getSource().handleChange(type, path);
    }

    @Override
    public void broadcastWatchingError() {
        broadcaster.getSource().stopWatchingAfterError();
    }
}
