package com.tyron.builder.api.execution.plan;

import com.tyron.builder.api.internal.tasks.WorkDependencyResolver;

/**
 * Resolves dependencies to {@link Node} objects.
 */
public interface DependencyResolver extends WorkDependencyResolver<Node> {
}