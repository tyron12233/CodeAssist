package org.gradle.execution;

import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.api.internal.GradleInternal;

public interface BuildExecutionContext {
    GradleInternal getGradle();

    ExecutionPlan getExecutionPlan();

    void proceed();
}