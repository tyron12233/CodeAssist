package com.tyron.builder.composite.internal;
import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.internal.artifacts.DefaultBuildIdentifier;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.ExecutionResult;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.concurrent.ManagedExecutor;
import com.tyron.builder.internal.work.WorkerLeaseService;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

class DefaultBuildControllers implements BuildControllers {
    // Always iterate over the controllers in a fixed order
    private final Map<BuildIdentifier, BuildController> controllers = new TreeMap<>(idComparator());
    private final ManagedExecutor executorService;
    private final WorkerLeaseService workerLeaseService;

    DefaultBuildControllers(ManagedExecutor executorService, WorkerLeaseService workerLeaseService) {
        this.executorService = executorService;
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public BuildController getBuildController(BuildState build) {
        BuildController buildController = controllers.get(build.getBuildIdentifier());
        if (buildController != null) {
            return buildController;
        }

        BuildController newBuildController = new DefaultBuildController(build, workerLeaseService);
        controllers.put(build.getBuildIdentifier(), newBuildController);
        return newBuildController;
    }

    @Override
    public void populateWorkGraphs() {
        boolean tasksDiscovered = true;
        while (tasksDiscovered) {
            tasksDiscovered = false;
            for (BuildController buildController : ImmutableList.copyOf(controllers.values())) {
                if (buildController.scheduleQueuedTasks()) {
                    tasksDiscovered = true;
                }
            }
        }
        for (BuildController buildController : controllers.values()) {
            buildController.finalizeWorkGraph();
        }
    }

    @Override
    public void startExecution() {
        for (BuildController buildController : controllers.values()) {
            buildController.startExecution(executorService);
        }
    }

    @Override
    public ExecutionResult<Void> awaitCompletion() {
        ExecutionResult<Void> result = ExecutionResult.succeeded();
        for (BuildController buildController : controllers.values()) {
            result = result.withFailures(buildController.awaitCompletion());
        }
        return result;
    }

    @Override
    public void close() {
        CompositeStoppable.stoppable(controllers.values()).stop();
    }

    private Comparator<BuildIdentifier> idComparator() {
        return (id1, id2) -> {
            // Root is always last
            if (id1.equals(DefaultBuildIdentifier.ROOT)) {
                if (id2.equals(DefaultBuildIdentifier.ROOT)) {
                    return 0;
                } else {
                    return 1;
                }
            }
            if (id2.equals(DefaultBuildIdentifier.ROOT)) {
                return -1;
            }
            return id1.getName().compareTo(id2.getName());
        };
    }
}
