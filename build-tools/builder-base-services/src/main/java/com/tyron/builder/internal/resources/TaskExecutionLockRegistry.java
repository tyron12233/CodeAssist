package com.tyron.builder.internal.resources;

import com.tyron.builder.util.Path;

public class TaskExecutionLockRegistry extends AbstractResourceLockRegistry<Path, TaskExecutionLock> {
    private final ProjectLockRegistry projectLockRegistry;

    public TaskExecutionLockRegistry(ResourceLockCoordinationService coordinationService, ProjectLockRegistry projectLockRegistry) {
        super(coordinationService);
        this.projectLockRegistry = projectLockRegistry;
    }

    public ResourceLock getTaskExecutionLock(Path buildIdentityPath, Path projectIdentityPath) {
        if (projectLockRegistry.getAllowsParallelExecution()) {
            return projectLockRegistry.getResourceLock(buildIdentityPath, projectIdentityPath);
        } else {
            return getTaskExecutionLockForBuild(buildIdentityPath, projectIdentityPath);
        }
    }

    private ResourceLock getTaskExecutionLockForBuild(final Path buildIdentityPath, final Path projectIdentityPath) {
        return getOrRegisterResourceLock(buildIdentityPath,
                (projectPath, coordinationService, owner) -> new TaskExecutionLock("task execution for build " + buildIdentityPath.getPath(), projectLockRegistry.getResourceLock(buildIdentityPath, projectIdentityPath), coordinationService, owner));
    }
}
