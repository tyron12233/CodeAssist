package com.tyron.builder.api.internal.resources;

import com.tyron.builder.api.util.Path;

public class ProjectLockRegistry extends AbstractResourceLockRegistry<Path, ProjectLock> {
    private final boolean parallelEnabled;
    private final LockCache<Path, AllProjectsLock> allProjectsLocks;

    public ProjectLockRegistry(ResourceLockCoordinationService coordinationService, boolean parallelEnabled) {
        super(coordinationService);
        this.parallelEnabled = parallelEnabled;
        allProjectsLocks = new LockCache<Path, AllProjectsLock>(coordinationService, this);
    }

    public boolean getAllowsParallelExecution() {
        return parallelEnabled;
    }

    public ResourceLock getAllProjectsLock(final Path buildIdentityPath) {
        return allProjectsLocks.getOrRegisterResourceLock(buildIdentityPath, new ResourceLockProducer<Path, AllProjectsLock>() {
            @Override
            public AllProjectsLock create(Path key, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
                String displayName = "All projects of " + buildIdentityPath;
                return new AllProjectsLock(displayName, coordinationService, owner);
            }
        });
    }

    public ProjectLock getProjectLock(Path buildIdentityPath, Path projectIdentityPath) {
        return doGetResourceLock(buildIdentityPath, parallelEnabled ? projectIdentityPath : buildIdentityPath);
    }

    private ProjectLock doGetResourceLock(final Path buildIdentityPath, final Path lockPath) {
        return getOrRegisterResourceLock(lockPath, new ResourceLockProducer<Path, ProjectLock>() {
            @Override
            public ProjectLock create(Path projectPath, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
                String displayName = parallelEnabled ? "state of project " + lockPath : "state of build " + lockPath;
                return new ProjectLock(displayName, coordinationService, owner, getAllProjectsLock(buildIdentityPath));
            }
        });
    }
}