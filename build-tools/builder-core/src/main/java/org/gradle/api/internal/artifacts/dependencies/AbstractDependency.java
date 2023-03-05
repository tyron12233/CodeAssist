package org.gradle.api.internal.artifacts.dependencies;

import org.gradle.api.artifacts.Dependency;
import org.gradle.api.internal.artifacts.DependencyResolveContext;
import org.gradle.api.internal.artifacts.ResolvableDependency;

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
