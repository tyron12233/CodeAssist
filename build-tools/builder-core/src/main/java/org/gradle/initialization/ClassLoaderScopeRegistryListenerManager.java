package org.gradle.initialization;

import org.gradle.internal.event.AnonymousListenerBroadcast;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.UserHome.class)
public class ClassLoaderScopeRegistryListenerManager {
    private final AnonymousListenerBroadcast<ClassLoaderScopeRegistryListener> broadcaster;

    public ClassLoaderScopeRegistryListenerManager(ListenerManager manager) {
        broadcaster = manager.createAnonymousBroadcaster(ClassLoaderScopeRegistryListener.class);
    }

    public ClassLoaderScopeRegistryListener getBroadcaster() {
        return broadcaster.getSource();
    }

    public void add(ClassLoaderScopeRegistryListener listener) {
        broadcaster.add(listener);
    }

    public void remove(ClassLoaderScopeRegistryListener listener) {
        broadcaster.remove(listener);
    }
}
