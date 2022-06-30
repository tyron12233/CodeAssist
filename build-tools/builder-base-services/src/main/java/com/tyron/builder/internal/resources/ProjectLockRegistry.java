package com.tyron.builder.internal.resources;


import com.tyron.builder.util.Path;

public class ProjectLockRegistry extends AbstractResourceLockRegistry<Path, ProjectLock> {
    private final boolean parallelEnabled;
    private final AllProjectsLock allProjectsLock;

    public ProjectLockRegistry(ResourceLockCoordinationService coordinationService, boolean parallelEnabled) {
        super(coordinationService);
        this.parallelEnabled = parallelEnabled;
        this.allProjectsLock = new AllProjectsLock("All projects", coordinationService, this);
    }

    @Override
    public boolean hasOpenLocks() {
        if (super.hasOpenLocks()) {
            return true;
        }
        return allProjectsLock.isLocked();
    }

    public boolean getAllowsParallelExecution() {
        return parallelEnabled;
    }

    public ResourceLock getAllProjectsLock() {
        return allProjectsLock;
    }

    public ProjectLock getResourceLock(Path buildIdentityPath, Path projectIdentityPath) {
        return getResourceLock(parallelEnabled ? projectIdentityPath : buildIdentityPath);
    }

    private ProjectLock getResourceLock(final Path lockPath) {
        return getOrRegisterResourceLock(lockPath, new ResourceLockProducer<Path, ProjectLock>() {
            @Override
            public ProjectLock create(Path projectPath, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
                String displayName = parallelEnabled ? "state of project " + lockPath : "state of build " + lockPath;
                return new ProjectLock(displayName, coordinationService, owner, allProjectsLock);
            }
        });
    }
}
