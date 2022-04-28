package com.tyron.builder.execution;

import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;

public interface BuildExecutionContext {
    GradleInternal getGradle();

    ExecutionPlan getExecutionPlan();

    void proceed();
}