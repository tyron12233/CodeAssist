package com.tyron.builder.internal.execution;

public interface ExecutionResult {
    ExecutionOutcome getOutcome();

    Object getOutput();
}