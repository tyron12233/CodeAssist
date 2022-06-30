package com.tyron.builder.composite.internal;

import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.internal.service.scopes.BuildScopeServices;
import com.tyron.builder.util.Path;
import com.tyron.builder.initialization.exception.ExceptionAnalyser;
import com.tyron.builder.internal.build.AbstractBuildState;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.ExecutionResult;
import com.tyron.builder.internal.build.StandAloneNestedBuild;
import com.tyron.builder.internal.buildtree.BuildModelParameters;
import com.tyron.builder.internal.buildtree.BuildTreeFinishExecutor;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleControllerFactory;
import com.tyron.builder.internal.buildtree.BuildTreeState;
import com.tyron.builder.internal.buildtree.BuildTreeWorkExecutor;
import com.tyron.builder.internal.buildtree.DefaultBuildTreeWorkExecutor;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;
import java.util.function.Function;

class DefaultNestedBuild extends AbstractBuildState implements StandAloneNestedBuild {
    private final Path identityPath;
    private final BuildState owner;
    private final BuildIdentifier buildIdentifier;
    private final BuildDefinition buildDefinition;
    private final BuildTreeLifecycleController buildTreeLifecycleController;

    DefaultNestedBuild(
            BuildIdentifier buildIdentifier,
            Path identityPath,
            BuildDefinition buildDefinition,
            BuildState owner,
            BuildTreeState buildTree
    ) {
        super(buildTree, buildDefinition, owner);
        this.buildIdentifier = buildIdentifier;
        this.identityPath = identityPath;
        this.buildDefinition = buildDefinition;
        this.owner = owner;

        BuildScopeServices buildScopeServices = getBuildServices();
        ExceptionAnalyser exceptionAnalyser = buildScopeServices.get(ExceptionAnalyser.class);
        BuildModelParameters modelParameters = buildScopeServices.get(BuildModelParameters.class);
        BuildTreeWorkExecutor workExecutor = new DefaultBuildTreeWorkExecutor();
        BuildTreeLifecycleControllerFactory buildTreeLifecycleControllerFactory = buildScopeServices.get(BuildTreeLifecycleControllerFactory.class);

        // On completion of the action, finish only this build and do not finish any other builds
        // When the build model is required, then do not finish anything on completion of the action
        // The root build will take care of finishing this build later, if not finished now
        BuildTreeFinishExecutor finishExecutor;
        if (modelParameters.isRequiresBuildModel()) {
            finishExecutor = new DoNothingBuildFinishExecutor(exceptionAnalyser);
        } else {
            finishExecutor = new FinishThisBuildOnlyFinishExecutor(exceptionAnalyser);
        }
        buildTreeLifecycleController = buildTreeLifecycleControllerFactory.createController(getBuildController(), workExecutor, finishExecutor);
    }

    @Override
    public BuildIdentifier getBuildIdentifier() {
        return buildIdentifier;
    }

    @Override
    public Path getIdentityPath() {
        return identityPath;
    }

    @Override
    public boolean isImplicitBuild() {
        return true;
    }

    @Override
    public ExecutionResult<Void> finishBuild() {
        return getBuildController().finishBuild(null);
    }

    @Override
    public <T> T run(Function<? super BuildTreeLifecycleController, T> buildAction) {
        return buildAction.apply(buildTreeLifecycleController);
    }

    @Override
    public Path getCurrentPrefixForProjectsInChildBuilds() {
        return owner.getCurrentPrefixForProjectsInChildBuilds().child(buildDefinition.getName());
    }

    @Override
    public Path calculateIdentityPathForProject(Path projectPath) {
        return getBuildController().getGradle().getIdentityPath().append(projectPath);
    }

    @Override
    public File getBuildRootDir() {
        return buildDefinition.getBuildRootDir();
    }

    private static class DoNothingBuildFinishExecutor implements BuildTreeFinishExecutor {
        private final ExceptionAnalyser exceptionAnalyser;

        public DoNothingBuildFinishExecutor(ExceptionAnalyser exceptionAnalyser) {
            this.exceptionAnalyser = exceptionAnalyser;
        }

        @Override
        @Nullable
        public RuntimeException finishBuildTree(List<Throwable> failures) {
            return exceptionAnalyser.transform(failures);
        }
    }

    private class FinishThisBuildOnlyFinishExecutor implements BuildTreeFinishExecutor {
        private final ExceptionAnalyser exceptionAnalyser;

        public FinishThisBuildOnlyFinishExecutor(ExceptionAnalyser exceptionAnalyser) {
            this.exceptionAnalyser = exceptionAnalyser;
        }

        @Override
        @Nullable
        public RuntimeException finishBuildTree(List<Throwable> failures) {
            RuntimeException reportable = exceptionAnalyser.transform(failures);
            ExecutionResult<Void> finishResult = getBuildController().finishBuild(reportable);
            return exceptionAnalyser.transform(ExecutionResult.maybeFailed(reportable).withFailures(finishResult).getFailures());
        }
    }
}