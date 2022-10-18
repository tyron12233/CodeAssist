package org.gradle.api.internal.artifacts;

public interface ResolvableDependency {
    void resolve(DependencyResolveContext context);
}
