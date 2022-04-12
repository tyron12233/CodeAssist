package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.ProjectState;
import com.tyron.builder.api.initialization.ProjectDescriptor;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.reflect.DirectInstantiator;
import com.tyron.builder.api.internal.reflect.Instantiator;
import com.tyron.builder.initialization.DefaultProjectDescriptor;
import com.tyron.builder.util.internal.NameValidator;

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
                gradle,
                owner,
                gradle.getServiceRegistryFactory()
        );
        project.beforeEvaluate(p -> {
            NameValidator.validate(project.getName(), "project name", DefaultProjectDescriptor.INVALID_NAME_IN_INCLUDE_HINT);
        });
        gradle.getProjectRegistry().addProject(project);
        return project;
    }
}
