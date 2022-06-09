package com.tyron.builder.internal.resources;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.UncheckedException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

public abstract class AbstractResourceLockRegistry<K, T extends ResourceLock> implements ResourceLockRegistry, ResourceLockContainer {
    private final Cache<K, T> resourceLocks = CacheBuilder.newBuilder().weakValues().build();
    private final ConcurrentMap<Long, ThreadLockDetails> threadLocks = new ConcurrentHashMap<Long, ThreadLockDetails>();
    private final ResourceLockCoordinationService coordinationService;

    public AbstractResourceLockRegistry(final ResourceLockCoordinationService coordinationService) {
        this.coordinationService = coordinationService;
    }

    protected T getOrRegisterResourceLock(final K key, final ResourceLockProducer<K, T> producer) {
        try {
            return resourceLocks.get(key, new Callable<T>() {
                @Override
                public T call() {
                    return createResourceLock(key, producer);
                }
            });
        } catch (ExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    protected T createResourceLock(final K key, final ResourceLockProducer<K, T> producer) {
        return producer.create(key, coordinationService, this);
    }

    @Override
    public Collection<? extends ResourceLock> getResourceLocksByCurrentThread() {
        return ImmutableList.copyOf(detailsForCurrentThread().locks);
    }

    public <S> S whileDisallowingLockChanges(Factory<S> action) {
        ThreadLockDetails lockDetails = detailsForCurrentThread();
        boolean previous = lockDetails.mayChange;
        lockDetails.mayChange = false;
        try {
            return action.create();
        } finally {
            lockDetails.mayChange = previous;
        }
    }

    public <S> S allowUncontrolledAccessToAnyResource(Factory<S> factory) {
        ThreadLockDetails lockDetails = detailsForCurrentThread();
        boolean previous = lockDetails.canAccessAnything;
        lockDetails.canAccessAnything = true;
        try {
            return factory.create();
        } finally {
            lockDetails.canAccessAnything = previous;
        }
    }

    @Override
    public boolean hasOpenLocks() {
        for (ResourceLock resourceLock : resourceLocks.asMap().values()) {
            if (resourceLock.isLocked()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void lockAcquired(ResourceLock resourceLock) {
        ThreadLockDetails lockDetails = detailsForCurrentThread();
        if (!lockDetails.mayChange) {
            throw new IllegalStateException("This thread may not acquire more locks.");
        }
        lockDetails.locks.add(resourceLock);
    }

    public boolean holdsLock() {
        ThreadLockDetails details = detailsForCurrentThread();
        return !details.locks.isEmpty();
    }

    private ThreadLockDetails detailsForCurrentThread() {
        long id = Thread.currentThread().getId();
        ThreadLockDetails lockDetails = threadLocks.get(id);
        if (lockDetails == null) {
            lockDetails = new ThreadLockDetails();
            threadLocks.put(id, lockDetails);
        }
        return lockDetails;
    }

    @Override
    public void lockReleased(ResourceLock resourceLock) {
        ThreadLockDetails lockDetails = threadLocks.get(Thread.currentThread().getId());
        if (!lockDetails.mayChange) {
            throw new IllegalStateException("This thread may not release any locks.");
        }
        lockDetails.locks.remove(resourceLock);
    }

    public boolean mayAttemptToChangeLocks() {
        ThreadLockDetails details = detailsForCurrentThread();
        return details.mayChange && !details.canAccessAnything;
    }

    public boolean isAllowedUncontrolledAccessToAnyResource() {
        return detailsForCurrentThread().canAccessAnything;
    }

    public interface ResourceLockProducer<K, T extends ResourceLock> {
        T create(K key, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner);
    }

    private static class ThreadLockDetails {
        // Only accessed by the thread itself, so does not require synchronization
        private boolean mayChange = true;
        private boolean canAccessAnything = false;
        private final List<ResourceLock> locks = new ArrayList<ResourceLock>();
    }
}
