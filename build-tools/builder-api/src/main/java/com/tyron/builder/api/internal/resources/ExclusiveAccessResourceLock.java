package com.tyron.builder.api.internal.resources;

public class ExclusiveAccessResourceLock extends AbstractTrackedResourceLock {
    private Thread owner;

    public ExclusiveAccessResourceLock(String displayName, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
        super(displayName, coordinationService, owner);
    }

    @Override
    protected boolean acquireLock() {
        Thread currentThread = Thread.currentThread();
        if (owner == currentThread) {
            return true;
        }
        if (owner == null && canAcquire()) {
            owner = currentThread;
            return true;
        }
        return false;
    }

    protected boolean canAcquire() {
        return true;
    }

    @Override
    protected void releaseLock() {
        owner = null;
    }

    @Override
    protected boolean doIsLockedByCurrentThread() {
        return owner == Thread.currentThread();
    }

    @Override
    protected boolean doIsLocked() {
        return owner != null;
    }
}