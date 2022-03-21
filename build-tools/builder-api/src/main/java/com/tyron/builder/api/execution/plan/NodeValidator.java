package com.tyron.builder.api.execution.plan;


public interface NodeValidator {
    boolean hasValidationProblems(LocalTaskNode node);
}