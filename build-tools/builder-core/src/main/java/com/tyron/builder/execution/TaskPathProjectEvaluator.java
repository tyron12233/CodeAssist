package com.tyron.builder.execution;

import com.tyron.builder.api.BuildCancelledException;
import com.tyron.builder.execution.ProjectConfigurer;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.BuildProject;

public class TaskPathProjectEvaluator implements ProjectConfigurer {
    private final BuildCancellationToken cancellationToken;

    public TaskPathProjectEvaluator(BuildCancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
    }

    @Override
    public void configure(ProjectInternal project) {
        project.getOwner().ensureConfigured();
    }

    @Override
    public void configureFully(ProjectInternal project) {
        configure(project);
        if (cancellationToken.isCancellationRequested()) {
            throw new BuildCancelledException();
        }
        project.getOwner().ensureTasksDiscovered();
    }

    @Override
    public void configureHierarchy(ProjectInternal project) {
        configure(project);
        for (BuildProject sub : project.getSubprojects()) {
            configure((ProjectInternal) sub);
        }
    }

    @Override
    public void configureHierarchyFully(ProjectInternal project) {
        configureFully(project);
        for (BuildProject sub : project.getSubprojects()) {
            configureFully((ProjectInternal) sub);
        }
    }
}
