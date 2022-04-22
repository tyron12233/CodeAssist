package com.tyron.builder.execution.plan;


public interface NodeValidator {
    boolean hasValidationProblems(LocalTaskNode node);
}