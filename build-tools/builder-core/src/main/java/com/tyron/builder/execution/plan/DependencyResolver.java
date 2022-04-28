package com.tyron.builder.execution.plan;

import com.tyron.builder.api.internal.tasks.WorkDependencyResolver;

/**
 * Resolves dependencies to {@link Node} objects.
 */
public interface DependencyResolver extends WorkDependencyResolver<Node> {
}