package org.gradle.execution;

import org.gradle.api.BuildCancelledException;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.Project;

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
        for (Project sub : project.getSubprojects()) {
            configure((ProjectInternal) sub);
        }
    }

    @Override
    public void configureHierarchyFully(ProjectInternal project) {
        configureFully(project);
        for (Project sub : project.getSubprojects()) {
            configureFully((ProjectInternal) sub);
        }
    }
}
