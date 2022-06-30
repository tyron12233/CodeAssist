package com.tyron.builder.initialization;

import com.tyron.builder.internal.event.AnonymousListenerBroadcast;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

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
