package com.tyron.builder.api.internal.resources;

public class TaskExecutionLock extends ExclusiveAccessResourceLock {
    private final ProjectLock stateLock;

    public TaskExecutionLock(String displayName, ProjectLock stateLock, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
        super(displayName, coordinationService, owner);
        this.stateLock = stateLock;
    }

    @Override
    protected boolean canAcquire() {
        return stateLock.isLockedByCurrentThread() || stateLock.tryLock();
    }

    @Override
    protected void releaseLock() {
        super.releaseLock();
        stateLock.unlock();
    }
}