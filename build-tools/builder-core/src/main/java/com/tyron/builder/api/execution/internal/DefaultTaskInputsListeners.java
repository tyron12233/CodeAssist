package com.tyron.builder.api.execution.internal;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.internal.event.AnonymousListenerBroadcast;
import com.tyron.builder.internal.event.ListenerManager;

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
