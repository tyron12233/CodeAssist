package com.tyron.builder.internal.buildtree;

import com.tyron.builder.internal.Describables;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.composite.internal.BuildTreeWorkGraphController;
import com.tyron.builder.internal.build.BuildLifecycleController;
import com.tyron.builder.internal.build.ExecutionResult;
import com.tyron.builder.internal.model.StateTransitionController;
import com.tyron.builder.internal.model.StateTransitionControllerFactory;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class DefaultBuildTreeLifecycleController implements BuildTreeLifecycleController {
    private enum State implements StateTransitionController.State {
        NotStarted, Complete
    }

    private final BuildLifecycleController buildLifecycleController;
    private final BuildTreeWorkGraphController taskGraph;
    private final BuildTreeWorkPreparer workPreparer;
    private final BuildTreeWorkExecutor workExecutor;
    private final BuildTreeModelCreator modelCreator;
    private final BuildTreeFinishExecutor finishExecutor;
    private final StateTransitionController<State> state;

    public DefaultBuildTreeLifecycleController(
            BuildLifecycleController buildLifecycleController,
            BuildTreeWorkGraphController taskGraph,
            BuildTreeWorkPreparer workPreparer,
            BuildTreeWorkExecutor workExecutor,
            BuildTreeModelCreator modelCreator,
            BuildTreeFinishExecutor finishExecutor,
            StateTransitionControllerFactory controllerFactory
    ) {
        this.buildLifecycleController = buildLifecycleController;
        this.taskGraph = taskGraph;
        this.workPreparer = workPreparer;
        this.modelCreator = modelCreator;
        this.workExecutor = workExecutor;
        this.finishExecutor = finishExecutor;
        this.state = controllerFactory.newController(Describables.of("build tree state"), State.NotStarted);
    }

    @Override
    public void beforeBuild(Consumer<? super GradleInternal> action) {
        state.inState(State.NotStarted, () -> action.accept(buildLifecycleController.getGradle()));
    }

    @Override
    public void scheduleAndRunTasks() {
        runBuild(this::doScheduleAndRunTasks);
    }

    @Override
    public <T> T fromBuildModel(boolean runTasks, BuildTreeModelAction<? extends T> action) {
        return runBuild(() -> {
            modelCreator.beforeTasks(action);
            if (runTasks) {
                ExecutionResult<Void> result = doScheduleAndRunTasks();
                if (!result.getFailures().isEmpty()) {
                    return result.asFailure();
                }
            }
            T model = modelCreator.fromBuildModel(action);
            return ExecutionResult.succeeded(model);
        });
    }

    private ExecutionResult<Void> doScheduleAndRunTasks() {
        return taskGraph.withNewWorkGraph(graph -> {
            workPreparer.scheduleRequestedTasks(graph);
            return workExecutor.execute(graph);
        });
    }

    @Override
    public <T> T withEmptyBuild(Function<? super SettingsInternal, T> action) {
        return runBuild(() -> {
            T result = buildLifecycleController.withSettings(action);
            return ExecutionResult.succeeded(result);
        });
    }

    private <T> T runBuild(Supplier<ExecutionResult<? extends T>> action) {
        return state.transition(State.NotStarted, State.Complete, () -> {
            ExecutionResult<? extends T> result;
            try {
                result = action.get();
            } catch (Throwable t) {
                result = ExecutionResult.failed(t);
            }

            RuntimeException finalReportableFailure = finishExecutor.finishBuildTree(result.getFailures());
            if (finalReportableFailure != null) {
                throw finalReportableFailure;
            }

            return result.getValue();
        });
    }
}