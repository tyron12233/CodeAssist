package com.tyron.builder.internal.buildtree;

import com.tyron.builder.internal.build.BuildLifecycleController;
import com.tyron.builder.internal.build.BuildState;

public class DefaultBuildTreeWorkPreparer implements BuildTreeWorkPreparer {
    private final BuildState targetBuild;
    private final BuildLifecycleController buildController;

    public DefaultBuildTreeWorkPreparer(BuildState targetBuild, BuildLifecycleController buildLifecycleController) {
        this.targetBuild = targetBuild;
        this.buildController = buildLifecycleController;
    }

    @Override
    public void scheduleRequestedTasks(BuildTreeWorkGraph graph) {
        buildController.prepareToScheduleTasks();
        graph.scheduleWork(builder -> builder.withWorkGraph(targetBuild, BuildLifecycleController.WorkGraphBuilder::addRequestedTasks));
    }
}
