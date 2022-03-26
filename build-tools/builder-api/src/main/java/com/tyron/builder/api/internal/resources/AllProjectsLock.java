package com.tyron.builder.api.internal.resources;

class AllProjectsLock extends ExclusiveAccessResourceLock {
    public AllProjectsLock(String displayName, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
        super(displayName, coordinationService, owner);
    }

    @Override
    protected boolean canAcquire() {
        // TODO - should block while some other thread holds a project lock
        return true;
    }
}