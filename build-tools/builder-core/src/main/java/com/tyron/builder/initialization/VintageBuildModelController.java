package com.tyron.builder.initialization;

import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.internal.Describables;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.configuration.ProjectsPreparer;
import com.tyron.builder.internal.build.BuildModelController;
import com.tyron.builder.internal.model.StateTransitionController;
import com.tyron.builder.internal.model.StateTransitionControllerFactory;

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

