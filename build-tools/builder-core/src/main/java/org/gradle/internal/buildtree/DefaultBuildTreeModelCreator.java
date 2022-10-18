package org.gradle.internal.buildtree;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.project.ProjectState;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.resources.ProjectLeaseRegistry;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildToolingModelController;
import org.gradle.tooling.provider.model.UnknownModelException;
import org.gradle.tooling.provider.model.internal.ToolingModelScope;

import java.util.Collection;

public class DefaultBuildTreeModelCreator implements BuildTreeModelCreator {
    private final BuildState defaultTarget;
    private final ProjectLeaseRegistry projectLeaseRegistry;
    private final BuildOperationExecutor buildOperationExecutor;
    private final boolean parallelActions;

    public DefaultBuildTreeModelCreator(
            BuildModelParameters buildModelParameters,
            BuildState defaultTarget,
            BuildOperationExecutor buildOperationExecutor,
            ProjectLeaseRegistry projectLeaseRegistry
    ) {
        this.defaultTarget = defaultTarget;
        this.projectLeaseRegistry = projectLeaseRegistry;
        this.buildOperationExecutor = buildOperationExecutor;
        this.parallelActions = buildModelParameters.isParallelToolingApiActions();
    }

    @Override
    public <T> void beforeTasks(BuildTreeModelAction<? extends T> action) {
        action.beforeTasks(new DefaultBuildTreeModelController());
    }

    @Override
    public <T> T fromBuildModel(BuildTreeModelAction<? extends T> action) {
        return action.fromBuildModel(new DefaultBuildTreeModelController());
    }

    private class DefaultBuildTreeModelController implements BuildTreeModelController {
        @Override
        public GradleInternal getConfiguredModel() {
            return defaultTarget.withToolingModels(BuildToolingModelController::getConfiguredModel);
        }

        @Override
        public ToolingModelScope locateBuilderForDefaultTarget(String modelName, boolean param) throws UnknownModelException {
            return locateBuilderForTarget(defaultTarget, modelName, param);
        }

        @Override
        public ToolingModelScope locateBuilderForTarget(BuildState target, String modelName, boolean param) throws UnknownModelException {
            return target.withToolingModels(controller -> controller.locateBuilderForTarget(modelName, param));
        }

        @Override
        public ToolingModelScope locateBuilderForTarget(ProjectState target, String modelName, boolean param) throws UnknownModelException {
            return target.getOwner().withToolingModels(controller -> controller.locateBuilderForTarget(target, modelName, param));
        }

        @Override
        public boolean queryModelActionsRunInParallel() {
            return projectLeaseRegistry.getAllowsParallelExecution() && parallelActions;
        }

        @Override
        public void runQueryModelActions(Collection<? extends RunnableBuildOperation> actions) {
            if (queryModelActionsRunInParallel()) {
                buildOperationExecutor.runAllWithAccessToProjectState(buildOperationQueue -> {
                    for (RunnableBuildOperation action : actions) {
                        buildOperationQueue.add(action);
                    }
                });
            } else {
                for (RunnableBuildOperation action : actions) {
                    try {
                        action.run(null);
                    } catch (Exception e) {
                        throw UncheckedException.throwAsUncheckedException(e);
                    }
                }
            }
        }
    }
}