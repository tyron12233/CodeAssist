package org.gradle.internal.resources;

import com.google.common.collect.Maps;
import org.gradle.internal.Pair;

import java.util.Map;
import java.util.concurrent.Semaphore;

public class SharedResourceLeaseRegistry extends AbstractResourceLockRegistry<String, SharedResourceLeaseRegistry.SharedResourceLease> {
    private final Map<String, Pair<Integer, Semaphore>> sharedResources = Maps.newConcurrentMap();

    public SharedResourceLeaseRegistry(ResourceLockCoordinationService coordinationService) {
        super(coordinationService);
    }

    public void registerSharedResource(String name, int leases) {
        sharedResources.put(name, Pair.of(leases, new Semaphore(leases)));
    }

    public ResourceLock getResourceLock(final String sharedResource, final int leases) {
        String displayName = "lease of " + leases + " for " + sharedResource;

        // We don't want to cache lock instances here since it's valid for multiple threads to hold a lock on a given resource for a given number of leases.
        // For that reason we don't want to reuse lock instances, as it's very possible they can be concurrently held by multiple threads.
        return createResourceLock(displayName, new ResourceLockProducer<String, SharedResourceLease>() {
            @Override
            public SharedResourceLease create(String displayName, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
                return new SharedResourceLease(displayName, coordinationService, owner, sharedResource, leases);
            }
        });
    }

    public class SharedResourceLease extends AbstractTrackedResourceLock {
        private final int leases;
        private final Pair<Integer, Semaphore> semaphore;
        private Thread ownerThread;
        private boolean active = false;

        SharedResourceLease(String displayName, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner, String sharedResource, int leases) {
            super(displayName, coordinationService, owner);
            this.leases = leases;
            this.semaphore = sharedResources.get(sharedResource);
        }

        @Override
        protected boolean acquireLock() {
            if (leases > semaphore.getLeft()) {
                throw new IllegalArgumentException("Cannot acquire lock on " + getDisplayName() + " as max available leases is " + semaphore.getLeft());
            }

            if (semaphore.getRight().tryAcquire(leases)) {
                active = true;
                ownerThread = Thread.currentThread();
            }

            return doIsLockedByCurrentThread();
        }

        @Override
        protected void releaseLock() {
            if (Thread.currentThread() != ownerThread) {
                throw new UnsupportedOperationException("Lock cannot be released from non-owner thread.");
            }

            semaphore.getRight().release(leases);
            active = false;
            ownerThread = null;
        }

        @Override
        protected boolean doIsLocked() {
            return active;
        }

        @Override
        protected boolean doIsLockedByCurrentThread() {
            return active && ownerThread == Thread.currentThread();
        }
    }
}
