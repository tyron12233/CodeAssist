package org.gradle.internal.execution.steps;

import org.gradle.internal.execution.UnitOfWork;

public interface Step<C extends Context, R extends Result> {
    R execute(UnitOfWork work, C context);
}