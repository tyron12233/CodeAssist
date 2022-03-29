package com.tyron.builder.api.internal.execution;

public interface ExecutionResult {
    ExecutionOutcome getOutcome();

    Object getOutput();
}