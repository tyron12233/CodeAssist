package org.gradle.internal.service.scopes;

import org.gradle.internal.event.AnonymousListenerBroadcast;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.watch.registry.FileWatcherRegistry;
import org.gradle.internal.watch.vfs.FileChangeListener;
import org.gradle.internal.watch.vfs.FileChangeListeners;

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
