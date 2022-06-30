package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.internal.process.ArgCollector;
import com.tyron.builder.process.ExecSpec;

public class ExecSpecBackedArgCollector implements ArgCollector {
    private final ExecSpec action;

    public ExecSpecBackedArgCollector(ExecSpec action) {
        this.action = action;
    }

    @Override
    public ArgCollector args(Object... args) {
        action.args(args);
        return this;
    }

    @Override
    public ArgCollector args(Iterable<?> args) {
        action.args(args);
        return this;
    }
}
