package com.tyron.builder.api.configuration.project;


import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectStateInternal;

import java.util.Arrays;
import java.util.List;

public class ConfigureActionsProjectEvaluator implements ProjectEvaluator {
    private final List<ProjectConfigureAction> configureActions;

    public ConfigureActionsProjectEvaluator(ProjectConfigureAction... configureActions) {
        this.configureActions = Arrays.asList(configureActions);
    }

    @Override
    public void evaluate(ProjectInternal project, ProjectStateInternal state) {
        for (ProjectConfigureAction configureAction : configureActions) {
            configureAction.execute(project);
        }
    }
}
