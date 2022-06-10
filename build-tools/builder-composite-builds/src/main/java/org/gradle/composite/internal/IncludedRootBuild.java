package org.gradle.composite.internal;

import com.google.common.base.Preconditions;
import org.gradle.api.Task;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.internal.tasks.TaskDependencyContainer;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.tasks.TaskReference;
import org.gradle.util.Path;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.CompositeBuildParticipantBuildState;
import org.gradle.internal.composite.IncludedBuildInternal;

import java.io.File;

public class IncludedRootBuild implements IncludedBuildInternal {
    private final CompositeBuildParticipantBuildState rootBuild;

    public IncludedRootBuild(CompositeBuildParticipantBuildState rootBuild) {
        this.rootBuild = rootBuild;
    }

    public CompositeBuildParticipantBuildState getRootBuild() {
        return rootBuild;
    }

    @Override
    public String getName() {
        return rootBuild.getProjects().getRootProject().getName();
    }

    @Override
    public File getProjectDir() {
        return rootBuild.getBuildRootDir();
    }

    @Override
    public TaskReference task(String path) {
        Preconditions.checkArgument(path.startsWith(":"), "Task path '%s' is not a qualified task path (e.g. ':task' or ':project:task').", path);
        return new IncludedRootBuildTaskReference(rootBuild, path);
    }

    @Override
    public BuildState getTarget() {
        return rootBuild;
    }

    private static class IncludedRootBuildTaskReference implements TaskReference, TaskDependencyContainer {
        private final String taskPath;
        private final CompositeBuildParticipantBuildState rootBuildState;

        public IncludedRootBuildTaskReference(CompositeBuildParticipantBuildState rootBuildState, String taskPath) {
            this.rootBuildState = rootBuildState;
            this.taskPath = taskPath;
        }

        @Override
        public String getName() {
            return Path.path(taskPath).getName();
        }

        public BuildIdentifier getBuildIdentifier() {
            return rootBuildState.getBuildIdentifier();
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            context.add(resolveTask());
        }

        private Task resolveTask() {
            rootBuildState.ensureProjectsConfigured();
            return rootBuildState.getMutableModel().getRootProject().getTasks().getByPath(taskPath);
        }
    }
}