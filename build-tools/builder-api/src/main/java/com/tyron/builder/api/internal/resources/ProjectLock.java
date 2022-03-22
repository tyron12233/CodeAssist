package com.tyron.builder.api.internal.resources;

public class ProjectLock extends ExclusiveAccessResourceLock {
    private final ResourceLock allProjectsLock;

    public ProjectLock(String displayName,
                       ResourceLockCoordinationService coordinationService,
                       ResourceLockContainer owner,
                       ResourceLock allProjectsLock) {
        super(displayName, coordinationService, owner);
        this.allProjectsLock = allProjectsLock;
    }

    @Override
    protected boolean canAcquire() {
        // Either the "all projects" lock is not held, or it is held by this thread
        return !allProjectsLock.isLocked() || allProjectsLock.isLockedByCurrentThread();
    }
}