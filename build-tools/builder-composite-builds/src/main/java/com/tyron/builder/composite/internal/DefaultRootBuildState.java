package com.tyron.builder.composite.internal;

import com.tyron.builder.BuildResult;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.artifacts.DefaultBuildIdentifier;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.service.scopes.BuildScopeServices;
import com.tyron.builder.util.Path;
import com.tyron.builder.initialization.IncludedBuildSpec;
import com.tyron.builder.initialization.RootBuildLifecycleListener;
import com.tyron.builder.initialization.exception.ExceptionAnalyser;
import com.tyron.builder.initialization.layout.BuildLayout;
import com.tyron.builder.internal.build.BuildLifecycleController;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.RootBuildState;
import com.tyron.builder.internal.buildtree.BuildOperationFiringBuildTreeWorkExecutor;
import com.tyron.builder.internal.buildtree.BuildTreeFinishExecutor;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleControllerFactory;
import com.tyron.builder.internal.buildtree.BuildTreeState;
import com.tyron.builder.internal.buildtree.BuildTreeWorkExecutor;
import com.tyron.builder.internal.buildtree.DefaultBuildTreeFinishExecutor;
import com.tyron.builder.internal.buildtree.DefaultBuildTreeWorkExecutor;
import com.tyron.builder.internal.composite.IncludedBuildInternal;

import java.io.File;
import java.util.function.Function;

class DefaultRootBuildState extends AbstractCompositeParticipantBuildState implements RootBuildState {
    private final ListenerManager listenerManager;
    private final BuildTreeLifecycleController buildTreeLifecycleController;
    private boolean completed;

    DefaultRootBuildState(
            BuildDefinition buildDefinition,
            BuildTreeState buildTree,
            ListenerManager listenerManager
    ) {
        super(buildTree, buildDefinition, null);
        this.listenerManager = listenerManager;

        BuildScopeServices buildScopeServices = getBuildServices();
        BuildLifecycleController buildLifecycleController = getBuildController();
        ExceptionAnalyser exceptionAnalyser = buildScopeServices.get(ExceptionAnalyser.class);
        BuildOperationExecutor buildOperationExecutor = buildScopeServices.get(BuildOperationExecutor.class);
        BuildStateRegistry buildStateRegistry = buildScopeServices.get(BuildStateRegistry.class);
        BuildTreeLifecycleControllerFactory buildTreeLifecycleControllerFactory = buildScopeServices.get(BuildTreeLifecycleControllerFactory.class);
        BuildTreeWorkExecutor workExecutor = new BuildOperationFiringBuildTreeWorkExecutor(new DefaultBuildTreeWorkExecutor(), buildOperationExecutor);
        BuildTreeFinishExecutor finishExecutor = new DefaultBuildTreeFinishExecutor(buildStateRegistry, exceptionAnalyser, buildLifecycleController);
        this.buildTreeLifecycleController = buildTreeLifecycleControllerFactory.createRootBuildController(buildLifecycleController, workExecutor, finishExecutor);
    }

    @Override
    public BuildIdentifier getBuildIdentifier() {
        return DefaultBuildIdentifier.ROOT;
    }

    @Override
    public Path getIdentityPath() {
        return Path.ROOT;
    }

    @Override
    public boolean isImplicitBuild() {
        return false;
    }

    @Override
    public void assertCanAdd(IncludedBuildSpec includedBuildSpec) {
    }

    @Override
    public File getBuildRootDir() {
        return getBuildController().getGradle().getServices().get(BuildLayout.class).getRootDirectory();
    }

    @Override
    public IncludedBuildInternal getModel() {
        return new IncludedRootBuild(this);
    }

    @Override
    public <T> T run(Function<? super BuildTreeLifecycleController, T> action) {
        if (completed) {
            throw new IllegalStateException("Cannot run more than one action for a build.");
        }
        try {
            RootBuildLifecycleListener buildLifecycleListener = listenerManager.getBroadcaster(RootBuildLifecycleListener.class);
            buildLifecycleListener.afterStart();
            try {
                GradleInternal gradle = getBuildController().getGradle();
//                DefaultDeploymentRegistry deploymentRegistry = gradle.getServices().get(DefaultDeploymentRegistry.class);
//                gradle.addBuildListener(new InternalBuildAdapter() {
//                    @Override
//                    public void buildFinished(BuildResult result) {
//                        deploymentRegistry.buildFinished(result);
//                    }
//                });
                return action.apply(buildTreeLifecycleController);
            } finally {
                buildLifecycleListener.beforeComplete();
            }
        } finally {
            completed = true;
        }
    }

    @Override
    public StartParameterInternal getStartParameter() {
        return getBuildController().getGradle().getStartParameter();
    }

    @Override
    public Path getCurrentPrefixForProjectsInChildBuilds() {
        return Path.ROOT;
    }

    @Override
    public Path calculateIdentityPathForProject(Path path) {
        return path;
    }

    @Override
    protected void ensureChildBuildConfigured() {
        // nothing to do for the root build
    }
}
