package com.tyron.builder.configurationcache;

import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.resources.ProjectLeaseRegistry;
import com.tyron.builder.composite.internal.BuildTreeWorkGraphController;
import com.tyron.builder.internal.build.BuildLifecycleController;
import com.tyron.builder.internal.buildTree.BuildModelParameters;
import com.tyron.builder.internal.buildTree.BuildTreeFinishExecutor;
import com.tyron.builder.internal.buildTree.BuildTreeLifecycleController;
import com.tyron.builder.internal.buildTree.BuildTreeLifecycleControllerFactory;
import com.tyron.builder.internal.buildTree.BuildTreeWorkExecutor;
import com.tyron.builder.internal.buildTree.DefaultBuildTreeLifecycleController;
import com.tyron.builder.internal.buildTree.DefaultBuildTreeModelCreator;
import com.tyron.builder.internal.buildTree.DefaultBuildTreeWorkPreparer;
import com.tyron.builder.internal.model.StateTransitionControllerFactory;

public class VintageBuildTreeLifecycleControllerFactory implements BuildTreeLifecycleControllerFactory {

    private final BuildModelParameters buildModelParameters;
    private final BuildTreeWorkGraphController taskGraph;
    private final BuildOperationExecutor buildOperationExecutor;
    private final ProjectLeaseRegistry projectLeaseRegistry;
    private final StateTransitionControllerFactory stateTransitionControllerFactory;

    public VintageBuildTreeLifecycleControllerFactory(BuildModelParameters buildModelParameters,
                                                      BuildTreeWorkGraphController taskGraph,
                                                      BuildOperationExecutor buildOperationExecutor,
                                                      ProjectLeaseRegistry projectLeaseRegistry,
                                                      StateTransitionControllerFactory stateTransitionControllerFactory) {
        this.buildModelParameters = buildModelParameters;
        this.taskGraph = taskGraph;
        this.buildOperationExecutor = buildOperationExecutor;
        this.projectLeaseRegistry = projectLeaseRegistry;
        this.stateTransitionControllerFactory = stateTransitionControllerFactory;
    }

    @Override
    public BuildTreeLifecycleController createRootBuildController(BuildLifecycleController targetBuild,
                                                                  BuildTreeWorkExecutor workExecutor,
                                                                  BuildTreeFinishExecutor finishExecutor) {
        return createController(targetBuild, workExecutor, finishExecutor);

    }

    @Override
    public BuildTreeLifecycleController createController(BuildLifecycleController targetBuild,
                                                         BuildTreeWorkExecutor workExecutor,
                                                         BuildTreeFinishExecutor finishExecutor) {
        DefaultBuildTreeWorkPreparer workPreparer = createWorkPreparer(targetBuild);
        DefaultBuildTreeModelCreator modelCreator = createModelCreator(targetBuild);
        return new DefaultBuildTreeLifecycleController(targetBuild, taskGraph, workPreparer, workExecutor, modelCreator, finishExecutor, stateTransitionControllerFactory);
    }


    private DefaultBuildTreeModelCreator createModelCreator(BuildLifecycleController targetBuild) {
        return new DefaultBuildTreeModelCreator(buildModelParameters, targetBuild.getGradle().getOwner(), buildOperationExecutor, projectLeaseRegistry);
    }

    private DefaultBuildTreeWorkPreparer createWorkPreparer(BuildLifecycleController targetBuild) {
        return new DefaultBuildTreeWorkPreparer(targetBuild.getGradle().getOwner(), targetBuild);
}
}
