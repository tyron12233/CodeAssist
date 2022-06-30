package com.tyron.builder.execution;

import com.google.common.collect.Lists;
import com.tyron.builder.api.internal.project.ProjectStateRegistry;
import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;

import java.util.List;

public class DefaultBuildConfigurationActionExecuter implements BuildConfigurationActionExecuter {
    private List<? extends BuildConfigurationAction> taskSelectors;
    private final ProjectStateRegistry projectStateRegistry;

    public DefaultBuildConfigurationActionExecuter(Iterable<? extends BuildConfigurationAction> defaultTaskSelectors, ProjectStateRegistry projectStateRegistry) {
        this.taskSelectors = Lists.newArrayList(defaultTaskSelectors);
        this.projectStateRegistry = projectStateRegistry;
    }

    @Override
    public void select(GradleInternal gradle, ExecutionPlan plan) {
        // We know that we're running single-threaded here, so we can use coarse grained locks
        projectStateRegistry.withMutableStateOfAllProjects(() -> {
            configure(taskSelectors, gradle, plan, 0);
        });
    }

    @Override
    public void setTaskSelectors(List<? extends BuildConfigurationAction> taskSelectors) {
        this.taskSelectors = taskSelectors;
    }

    private void configure(final List<? extends BuildConfigurationAction> processingConfigurationActions, final GradleInternal gradle, final ExecutionPlan plan, final int index) {
        if (index >= processingConfigurationActions.size()) {
            return;
        }
        processingConfigurationActions.get(index).configure(new BuildExecutionContext() {
            @Override
            public GradleInternal getGradle() {
                return gradle;
            }

            @Override
            public ExecutionPlan getExecutionPlan() {
                return plan;
            }

            @Override
            public void proceed() {
                configure(processingConfigurationActions, gradle, plan, index + 1);
            }

        });
    }
}


