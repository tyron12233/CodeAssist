package com.tyron.builder.composite.internal;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.tasks.TaskReference;
import com.tyron.builder.util.Path;
import com.tyron.builder.internal.build.IncludedBuildState;

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