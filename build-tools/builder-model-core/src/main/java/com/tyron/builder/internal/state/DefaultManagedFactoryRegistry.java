package com.tyron.builder.internal.state;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import javax.annotation.Nullable;

public class DefaultManagedFactoryRegistry implements ManagedFactoryRegistry {
    private final ManagedFactoryRegistry parent;
    private final Cache<Integer, ManagedFactory> managedFactoryCache = CacheBuilder.newBuilder().build();

    public DefaultManagedFactoryRegistry(ManagedFactoryRegistry parent) {
        this.parent = parent;
    }

    public DefaultManagedFactoryRegistry() {
        this(null);
    }

    public ManagedFactoryRegistry withFactories(ManagedFactory... factories) {
        for (ManagedFactory factory : factories) {
            register(factory);
        }
        return this;
    }

    @Override
    @Nullable
    public ManagedFactory lookup(int id) {
        ManagedFactory factory = managedFactoryCache.getIfPresent(id);
        if (factory == null && parent != null) {
            factory = parent.lookup(id);
        }
        return factory;
    }

    private void register(ManagedFactory factory) {
        ManagedFactory existing = managedFactoryCache.getIfPresent(factory.getId());
        if (existing != null) {
            throw new IllegalArgumentException("A managed factory with type " + existing.getClass().getSimpleName() + " (id: " + existing.getId() + ") has already been registered.");
        }
        managedFactoryCache.put(factory.getId(), factory);
    }
}
