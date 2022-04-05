package com.tyron.builder.api.internal.execution.steps;

import com.tyron.builder.api.internal.execution.UnitOfWork;

public interface Step<C extends Context, R extends Result> {
    R execute(UnitOfWork work, C context);
}