package com.tyron.builder.api.configuration.project;

import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectStateInternal;

public interface ProjectEvaluator {
    void evaluate(ProjectInternal project, ProjectStateInternal state);
}