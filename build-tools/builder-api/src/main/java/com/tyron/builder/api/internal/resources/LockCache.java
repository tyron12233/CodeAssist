package com.tyron.builder.api.internal.resources;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

public class LockCache<K, T extends ResourceLock> {
    private final Cache<K, T> resourceLocks = CacheBuilder.newBuilder().weakValues().build();
    private final ResourceLockCoordinationService coordinationService;
    private final ResourceLockContainer owner;

    public LockCache(ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
        this.coordinationService = coordinationService;
        this.owner = owner;
    }

    public T getOrRegisterResourceLock(final K key, final AbstractResourceLockRegistry.ResourceLockProducer<K, T> producer) {
        try {
            return resourceLocks.get(key, new Callable<T>() {
                @Override
                public T call() {
                    return createResourceLock(key, producer);
                }
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private T createResourceLock(final K key, final AbstractResourceLockRegistry.ResourceLockProducer<K, T> producer) {
        return producer.create(key, coordinationService, owner);
    }

    public Iterable<T> values() {
        return resourceLocks.asMap().values();
    }
}