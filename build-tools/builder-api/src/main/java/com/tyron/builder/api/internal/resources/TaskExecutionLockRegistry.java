package com.tyron.builder.api.internal.resources;

import com.tyron.builder.api.util.Path;

public class TaskExecutionLockRegistry extends AbstractResourceLockRegistry<Path, TaskExecutionLock> {
    private final ProjectLockRegistry projectLockRegistry;

    public TaskExecutionLockRegistry(ResourceLockCoordinationService coordinationService, ProjectLockRegistry projectLockRegistry) {
        super(coordinationService);
        this.projectLockRegistry = projectLockRegistry;
    }

    public ResourceLock getTaskExecutionLock(Path buildIdentityPath, final Path projectIdentityPath) {
        if (projectLockRegistry.getAllowsParallelExecution()) {
            return getTaskExecutionLockForProject(projectIdentityPath, projectIdentityPath, buildIdentityPath);
        } else {
            return getTaskExecutionLockForBuild(buildIdentityPath, projectIdentityPath);
        }
    }

    private TaskExecutionLock getTaskExecutionLockForProject(final Path projectIdentityPath, final Path projectIdentityPath1, final Path buildIdentityPath) {
        return getOrRegisterResourceLock(projectIdentityPath, new ResourceLockProducer<Path, TaskExecutionLock>() {
            @Override
            public TaskExecutionLock create(Path key, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
                return new TaskExecutionLock("task execution for " + projectIdentityPath.getPath(), projectLockRegistry.getProjectLock(buildIdentityPath, projectIdentityPath1), coordinationService, owner);
            }
        });
    }

    private ResourceLock getTaskExecutionLockForBuild(final Path buildIdentityPath, final Path projectIdentityPath) {
        return getOrRegisterResourceLock(buildIdentityPath, new ResourceLockProducer<Path, TaskExecutionLock>() {
            @Override
            public TaskExecutionLock create(Path projectPath, ResourceLockCoordinationService coordinationService, ResourceLockContainer owner) {
                return new TaskExecutionLock("task execution for build " + buildIdentityPath.getPath(), projectLockRegistry.getProjectLock(buildIdentityPath, projectIdentityPath), coordinationService, owner);
            }
        });
    }
}