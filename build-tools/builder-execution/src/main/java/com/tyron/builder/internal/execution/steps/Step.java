package com.tyron.builder.internal.execution.steps;

import com.tyron.builder.internal.execution.UnitOfWork;

public interface Step<C extends Context, R extends Result> {
    R execute(UnitOfWork work, C context);
}