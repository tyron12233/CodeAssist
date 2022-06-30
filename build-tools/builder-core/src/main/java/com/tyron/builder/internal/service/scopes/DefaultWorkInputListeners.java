package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.internal.event.AnonymousListenerBroadcast;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.internal.execution.steps.WorkInputListener;
import com.tyron.builder.internal.execution.steps.WorkInputListeners;

import java.util.EnumSet;

public class DefaultWorkInputListeners implements WorkInputListeners {
    private final AnonymousListenerBroadcast<WorkInputListener> broadcaster;

    public DefaultWorkInputListeners(ListenerManager listenerManager) {
        broadcaster = listenerManager.createAnonymousBroadcaster(WorkInputListener.class);
    }

    @Override
    public void addListener(WorkInputListener listener) {
        broadcaster.add(listener);
    }

    @Override
    public void removeListener(WorkInputListener listener) {
        broadcaster.remove(listener);
    }

    @Override
    public void broadcastFileSystemInputsOf(UnitOfWork work, EnumSet<InputFingerprinter.InputPropertyType> relevantTypes) {
        broadcaster.getSource().onExecute(work, relevantTypes);
    }
}