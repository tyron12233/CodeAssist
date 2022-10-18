package org.gradle.configurationcache

import org.gradle.composite.internal.BuildTreeWorkGraphController
import org.gradle.internal.build.BuildLifecycleController
import org.gradle.internal.buildtree.BuildModelParameters
import org.gradle.internal.buildtree.BuildTreeFinishExecutor
import org.gradle.internal.buildtree.BuildTreeLifecycleController
import org.gradle.internal.buildtree.BuildTreeLifecycleControllerFactory
import org.gradle.internal.buildtree.BuildTreeWorkExecutor
import org.gradle.internal.buildtree.DefaultBuildTreeLifecycleController
import org.gradle.internal.buildtree.DefaultBuildTreeModelCreator
import org.gradle.internal.buildtree.DefaultBuildTreeWorkPreparer
import org.gradle.internal.model.StateTransitionControllerFactory
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.resources.ProjectLeaseRegistry


class VintageBuildTreeLifecycleControllerFactory(
    private val buildModelParameters: BuildModelParameters,
    private val taskGraph: BuildTreeWorkGraphController,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val projectLeaseRegistry: ProjectLeaseRegistry,
    private val stateTransitionControllerFactory: StateTransitionControllerFactory
) : BuildTreeLifecycleControllerFactory {
    override fun createRootBuildController(targetBuild: BuildLifecycleController, workExecutor: BuildTreeWorkExecutor, finishExecutor: BuildTreeFinishExecutor): BuildTreeLifecycleController {
        return createController(targetBuild, workExecutor, finishExecutor)
    }

    override fun createController(targetBuild: BuildLifecycleController, workExecutor: BuildTreeWorkExecutor, finishExecutor: BuildTreeFinishExecutor): BuildTreeLifecycleController {
        val workPreparer = createWorkPreparer(targetBuild)
        val modelCreator = createModelCreator(targetBuild)
        return DefaultBuildTreeLifecycleController(targetBuild, taskGraph, workPreparer, workExecutor, modelCreator, finishExecutor, stateTransitionControllerFactory)
    }

    fun createModelCreator(targetBuild: BuildLifecycleController) =
        DefaultBuildTreeModelCreator(buildModelParameters, targetBuild.gradle.owner, buildOperationExecutor, projectLeaseRegistry)

    fun createWorkPreparer(targetBuild: BuildLifecycleController) = DefaultBuildTreeWorkPreparer(targetBuild.gradle.owner, targetBuild)
}
