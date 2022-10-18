package org.gradle.api.execution.internal;

import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.internal.event.AnonymousListenerBroadcast;
import org.gradle.internal.event.ListenerManager;

public class DefaultTaskInputsListeners implements TaskInputsListeners {

    private final AnonymousListenerBroadcast<TaskInputsListener> broadcaster;

    public DefaultTaskInputsListeners(ListenerManager listenerManager) {
        broadcaster = listenerManager.createAnonymousBroadcaster(TaskInputsListener.class);
    }

    @Override
    public void addListener(TaskInputsListener listener) {
        broadcaster.add(listener);
    }

    @Override
    public void removeListener(TaskInputsListener listener) {
        broadcaster.remove(listener);
    }

    @Override
    public void broadcastFileSystemInputsOf(TaskInternal task, FileCollectionInternal fileSystemInputs) {
        broadcaster.getSource().onExecute(task, fileSystemInputs);
    }
}
