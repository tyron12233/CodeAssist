package org.gradle.internal.execution;

public interface ExecutionResult {
    ExecutionOutcome getOutcome();

    Object getOutput();
}