package com.tyron.builder.internal.build;

import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;

/**
 * Transitions the model of an individual build in the build tree through its lifecycle.
 * See also {@link BuildTreeLifecycleController} and {@link BuildLifecycleController}.
 */
public interface BuildModelController {
    /**
     * Ensures the build's settings object has been configured.
     *
     * @return The settings.
     */
    SettingsInternal getLoadedSettings();

    /**
     * Ensures the build's projects have been configured.
     *
     * @return The gradle instance.
     */
    GradleInternal getConfiguredModel();

    /**
     * Does whatever work is required to allow tasks to be scheduled. May configure the build, if required.
     */
    void prepareToScheduleTasks();

    /**
     * Sets up the given execution plan before any work is added to it. Must call {@link #prepareToScheduleTasks()} prior to calling this method.
     */
    void initializeWorkGraph(ExecutionPlan plan);

    /**
     * Schedules the user requested tasks for this build into the given plan. Must call {@link  #initializeWorkGraph(ExecutionPlan)} prior to calling this method.
     */
    void scheduleRequestedTasks(ExecutionPlan plan);
}