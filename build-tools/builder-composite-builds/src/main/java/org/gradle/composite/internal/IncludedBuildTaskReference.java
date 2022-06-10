package org.gradle.composite.internal;

import org.gradle.api.Task;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.internal.tasks.TaskDependencyContainer;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.tasks.TaskReference;
import org.gradle.util.Path;
import org.gradle.internal.build.IncludedBuildState;

public class IncludedBuildTaskReference implements TaskReference, TaskDependencyContainer {
    private final String taskPath;
    private final IncludedBuildState includedBuild;

    public IncludedBuildTaskReference(IncludedBuildState includedBuild, String taskPath) {
        this.includedBuild = includedBuild;
        this.taskPath = taskPath;
    }

    @Override
    public String getName() {
        return Path.path(taskPath).getName();
    }

    public BuildIdentifier getBuildIdentifier() {
        return includedBuild.getBuildIdentifier();
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        context.add(resolveTask());
    }

    private Task resolveTask() {
        includedBuild.ensureProjectsConfigured();
        return includedBuild.getMutableModel().getRootProject().getTasks().getByPath(taskPath);
    }
}