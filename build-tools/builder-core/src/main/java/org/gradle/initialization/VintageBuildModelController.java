package org.gradle.initialization;

import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.internal.Describables;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.configuration.ProjectsPreparer;
import org.gradle.internal.build.BuildModelController;
import org.gradle.internal.model.StateTransitionController;
import org.gradle.internal.model.StateTransitionControllerFactory;

public class VintageBuildModelController implements BuildModelController {
    private enum Stage implements StateTransitionController.State {
        Created, SettingsLoaded, Configured
    }

    private final ProjectsPreparer projectsPreparer;
    private final GradleInternal gradle;
    private final TaskSchedulingPreparer taskGraphPreparer;
    private final SettingsPreparer settingsPreparer;
    private final TaskExecutionPreparer taskExecutionPreparer;
    private final StateTransitionController<Stage> state;

    public VintageBuildModelController(
            GradleInternal gradle,
            ProjectsPreparer projectsPreparer,
            TaskSchedulingPreparer taskSchedulingPreparer,
            SettingsPreparer settingsPreparer,
            TaskExecutionPreparer taskExecutionPreparer,
            StateTransitionControllerFactory controllerFactory
    ) {
        this.gradle = gradle;
        this.projectsPreparer = projectsPreparer;
        this.taskGraphPreparer = taskSchedulingPreparer;
        this.settingsPreparer = settingsPreparer;
        this.taskExecutionPreparer = taskExecutionPreparer;
        this.state = controllerFactory.newController(Describables.of("vintage state of", gradle.getOwner().getDisplayName()), Stage.Created);
    }

    @Override
    public SettingsInternal getLoadedSettings() {
        prepareSettings();
        return gradle.getSettings();
    }

    @Override
    public GradleInternal getConfiguredModel() {
        prepareSettings();
        prepareProjects();
        return gradle;
    }

    @Override
    public void prepareToScheduleTasks() {
        prepareSettings();
        prepareProjects();
    }

    @Override
    public void initializeWorkGraph(ExecutionPlan plan) {
        state.inState(Stage.Configured, () -> taskGraphPreparer.prepareForTaskScheduling(gradle, plan));
    }

    @Override
    public void scheduleRequestedTasks(ExecutionPlan plan) {
        state.inState(Stage.Configured, () -> taskExecutionPreparer.prepareForTaskExecution(gradle, plan));
    }

    private void prepareSettings() {
        state.transitionIfNotPreviously(Stage.Created, Stage.SettingsLoaded, () -> settingsPreparer.prepareSettings(gradle));
    }

    private void prepareProjects() {
        state.transitionIfNotPreviously(Stage.SettingsLoaded, Stage.Configured, () -> projectsPreparer.prepareProjects(gradle));
    }

}

