package org.gradle.api.artifacts.result;

import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.internal.scan.UsedByScanPlugin;

/**
 * An edge in the dependency graph. Provides information about the origin of the dependency and the requested component.
 *
 * @see ResolutionResult
 */
@UsedByScanPlugin
public interface DependencyResult {
    /**
     * <p>Returns the requested component.
     *
     * <p>The return type is declared as an opaque {@link org.gradle.api.artifacts.component.ComponentSelector}, however the selector may also implement one of the following interfaces:</p>
     *
     * <ul>
     *     <li>{@link org.gradle.api.artifacts.component.ProjectComponentSelector} for those dependencies that request a component from some other project in the current build.</li>
     *     <li>{@link org.gradle.api.artifacts.component.ModuleComponentSelector} for those dependencies that request a component to be found in some repository.</li>
     * </ul>
     * @return the requested component
     */
    ComponentSelector getRequested();

    /**
     * Returns the origin of the dependency.
     *
     * @return the origin of the dependency
     */
    ResolvedComponentResult getFrom();

    /**
     * Indicates if this dependency edge originated from a dependency constraint.
     *
     * @return true if the dependency is a constraint.
     * @since 5.1
     */
    boolean isConstraint();

}
