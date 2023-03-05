package org.gradle.configuration.project;

import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectStateInternal;

public interface ProjectEvaluator {
    void evaluate(ProjectInternal project, ProjectStateInternal state);
}