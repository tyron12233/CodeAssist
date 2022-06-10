package org.gradle.configurationcache;

import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.resources.ProjectLeaseRegistry;
import org.gradle.composite.internal.BuildTreeWorkGraphController;
import org.gradle.internal.build.BuildLifecycleController;
import org.gradle.internal.buildtree.BuildModelParameters;
import org.gradle.internal.buildtree.BuildTreeFinishExecutor;
import org.gradle.internal.buildtree.BuildTreeLifecycleController;
import org.gradle.internal.buildtree.BuildTreeLifecycleControllerFactory;
import org.gradle.internal.buildtree.BuildTreeWorkExecutor;
import org.gradle.internal.buildtree.DefaultBuildTreeLifecycleController;
import org.gradle.internal.buildtree.DefaultBuildTreeModelCreator;
import org.gradle.internal.buildtree.DefaultBuildTreeWorkPreparer;
import org.gradle.internal.model.StateTransitionControllerFactory;

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
