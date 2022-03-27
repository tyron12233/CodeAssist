package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.ProjectState;
import com.tyron.builder.api.initialization.ProjectDescriptor;
import com.tyron.builder.api.internal.GradleInternal;

import org.jetbrains.annotations.Nullable;

public class ProjectFactory implements IProjectFactory {
    @Override
    public ProjectInternal createProject(GradleInternal gradle,
                                         ProjectDescriptor descriptor,
                                         ProjectStateUnk owner,
                                         @Nullable ProjectInternal parent) {
        DefaultProject project = new DefaultProject(
                descriptor.getName(),
                parent,
                descriptor.getProjectDir(),
                descriptor.getBuildFile(),
                owner, gradle.getServiceRegistryFactory()
        );
        project.beforeEvaluate(p -> {

        });
        return project;
    }
}
