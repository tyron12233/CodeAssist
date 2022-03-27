package com.tyron.builder.api.internal.resources;

import java.util.logging.Logger;

public abstract class AbstractTrackedResourceLock implements ResourceLock {
    private static final Logger LOGGER = Logger.getLogger("TrackedResourceLock");

    private final String displayName;

    private final ResourceLockCoordinationService coordinationService;
    private final ResourceLockContainer owner;

    public AbstractTrackedResourceLock(String displayName, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
        this.displayName = displayName;
        this.coordinationService = coordinationService;
        this.owner = owner;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + getDisplayName();
    }

    @Override
    public boolean tryLock() {
        if (!isLockedByCurrentThread()) {
            if (acquireLock()) {
                LOGGER.info(Thread.currentThread().getName() + ": acquired lock on " + displayName);
                try {
                    owner.lockAcquired(this);
                } catch (RuntimeException e) {
                    releaseLock();
                    throw e;
                }
                coordinationService.getCurrent().registerLocked(this);
                return true;
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    @Override
    public void unlock() {
        if (isLockedByCurrentThread()) {
            releaseLock();
            LOGGER.info(Thread.currentThread() + ": released lock on " + displayName);
            try {
                owner.lockReleased(this);
            } finally {
                coordinationService.getCurrent().registerUnlocked(this);
            }
        }
    }

    @Override
    public boolean isLocked() {
        failIfNotInResourceLockStateChange();
        return doIsLocked();
    }

    @Override
    public boolean isLockedByCurrentThread() {
        failIfNotInResourceLockStateChange();
        return doIsLockedByCurrentThread();
    }

    private void failIfNotInResourceLockStateChange() {
        if (coordinationService.getCurrent() == null) {
            throw new IllegalStateException("No ResourceLockState is associated with this thread.");
        }
    }

    abstract protected boolean acquireLock();

    abstract protected void releaseLock();

    abstract protected boolean doIsLocked();

    abstract protected boolean doIsLockedByCurrentThread();

    @Override
    public String getDisplayName() {
        return displayName;
    }
}