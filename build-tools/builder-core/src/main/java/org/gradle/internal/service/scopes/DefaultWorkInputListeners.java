package org.gradle.internal.service.scopes;

import org.gradle.internal.event.AnonymousListenerBroadcast;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.fingerprint.InputFingerprinter;
import org.gradle.internal.execution.WorkInputListener;
import org.gradle.internal.execution.WorkInputListeners;

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