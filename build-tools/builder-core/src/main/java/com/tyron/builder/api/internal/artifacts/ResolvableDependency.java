package com.tyron.builder.api.internal.artifacts;

public interface ResolvableDependency {
    void resolve(DependencyResolveContext context);
}
