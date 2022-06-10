package org.gradle.execution.plan;

import org.gradle.api.internal.tasks.WorkDependencyResolver;

/**
 * Resolves dependencies to {@link Node} objects.
 */
public interface DependencyResolver extends WorkDependencyResolver<Node> {
}