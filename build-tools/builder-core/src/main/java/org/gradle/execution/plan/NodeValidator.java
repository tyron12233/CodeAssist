package org.gradle.execution.plan;

public interface NodeValidator {
    boolean hasValidationProblems(LocalTaskNode node);
}
