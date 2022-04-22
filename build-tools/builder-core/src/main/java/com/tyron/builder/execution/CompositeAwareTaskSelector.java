package com.tyron.builder.execution;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.IncludedBuildState;
import com.tyron.builder.util.Path;
import com.tyron.builder.util.Predicates;

import java.io.File;
import java.util.function.Predicate;

import javax.annotation.Nullable;

public class CompositeAwareTaskSelector extends TaskSelector {
    private final GradleInternal gradle;
    private final BuildStateRegistry buildStateRegistry;
    private final ProjectConfigurer projectConfigurer;
    private final TaskNameResolver taskNameResolver;

    public CompositeAwareTaskSelector(GradleInternal gradle, BuildStateRegistry buildStateRegistry, ProjectConfigurer projectConfigurer, TaskNameResolver taskNameResolver) {
        this.gradle = gradle;
        this.buildStateRegistry = buildStateRegistry;
        this.projectConfigurer = projectConfigurer;
        this.taskNameResolver = taskNameResolver;
    }

    @Override
    public Predicate<Task> getFilter(String path) {
        Path taskPath = Path.path(path);
        if (taskPath.isAbsolute()) {
            BuildState build = findIncludedBuild(taskPath);
            // Exclusion was for an included build, use it
            if (build != null) {
                return getSelectorForChildBuild(build).getFilter(taskPath.removeFirstSegments(1).toString());
            }
        }
        // Exclusion didn't match an included build, so it might be a subproject of the root build or a relative path
        if (gradle.isRootBuild()) {
            return getUnqualifiedBuildSelector().getFilter(path);
        } else {
            // Included build ignores this exclusion since it doesn't apply directly to it
            return Predicates.satisfyAll();
        }
    }

    @Override
    public TaskSelection getSelection(String path) {
        if (gradle.isRootBuild()) {
            Path taskPath = Path.path(path);
            if (taskPath.isAbsolute()) {
                BuildState build = findIncludedBuild(taskPath);
                if (build != null) {
                    return getSelectorForChildBuild(build).getSelection(taskPath.removeFirstSegments(1).toString());
                }
            }
        }

        return getUnqualifiedBuildSelector().getSelection(path);
    }

    @Override
    public TaskSelection getSelection(@Nullable String projectPath, @Nullable File root, String path) {
        if (gradle.isRootBuild()) {
            Path taskPath = Path.path(path);
            if (taskPath.isAbsolute()) {
                BuildState build = findIncludedBuild(taskPath);
                if (build != null) {
                    return getSelectorForChildBuild(build).getSelection(projectPath, root, taskPath.removeFirstSegments(1).toString());
                }
                build = findIncludedBuild(root);
                if (build != null) {
                    return getSelectorForChildBuild(build).getSelection(projectPath, root, path);
                }
            }
        }

        return getUnqualifiedBuildSelector().getSelection(projectPath, root, path);
    }

    @Nullable
    private BuildState findIncludedBuild(Path taskPath) {
        if (buildStateRegistry.getIncludedBuilds().isEmpty() || taskPath.segmentCount() <= 1) {
            return null;
        }

        String buildName = taskPath.segment(0);
        for (IncludedBuildState build : buildStateRegistry.getIncludedBuilds()) {
            if (build.getName().equals(buildName)) {
                return build;
            }
        }

        return null;
    }

    @Nullable
    private BuildState findIncludedBuild(@Nullable File root) {
        if (root == null) {
            return null;
        }

        for (IncludedBuildState build : buildStateRegistry.getIncludedBuilds()) {
            if (build.getRootDirectory().equals(root)) {
                return build;
            }
        }

        return null;
    }


    private TaskSelector getSelectorForChildBuild(BuildState buildState) {
        buildState.ensureProjectsConfigured();
        return getSelector(buildState);
    }

    private TaskSelector getSelector(BuildState buildState) {
        return new DefaultTaskSelector(buildState.getMutableModel(), taskNameResolver, projectConfigurer);
    }

    private TaskSelector getUnqualifiedBuildSelector() {
        return getSelector(gradle.getOwner());
    }
}
