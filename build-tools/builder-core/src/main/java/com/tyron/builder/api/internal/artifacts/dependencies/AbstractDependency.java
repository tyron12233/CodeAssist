package com.tyron.builder.api.internal.artifacts.dependencies;

import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.internal.artifacts.DependencyResolveContext;
import com.tyron.builder.api.internal.artifacts.ResolvableDependency;

public abstract class AbstractDependency implements ResolvableDependency, Dependency {
    private String reason;

    protected void copyTo(AbstractDependency target) {
        target.reason = reason;
    }

    @Override
    public void resolve(DependencyResolveContext context) {
    }

    @Override
    public String getReason() {
        return reason;
    }

    @Override
    public void because(String reason) {
        this.reason = reason;
    }
}
