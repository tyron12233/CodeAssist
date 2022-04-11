package com.tyron.builder.api.internal.resources;

public class DefaultLease extends AbstractTrackedResourceLock {
    private final LeaseHolder parent;
    private Thread ownerThread;

    public DefaultLease(String displayName, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner, LeaseHolder parent) {
        super(displayName, coordinationService, owner);
        this.parent = parent;
    }

    @Override
    protected boolean doIsLocked() {
        return ownerThread != null;
    }

    @Override
    protected boolean doIsLockedByCurrentThread() {
        return Thread.currentThread() == ownerThread;
    }

    @Override
    protected boolean acquireLock() {
        if (parent.grantLease()) {
            ownerThread = Thread.currentThread();
        }
        return ownerThread != null;
    }

    @Override
    protected void releaseLock() {
        if (Thread.currentThread() != ownerThread) {
            // Not implemented - not yet required. Please implement if required
            throw new UnsupportedOperationException("Must complete operation from owner thread.");
        }
        parent.releaseLease();
        ownerThread = null;
    }
}