package org.gradle.api.artifacts.result;

import org.gradle.api.artifacts.component.ComponentIdentifier;

/**
 * The result of resolving a component.
 *
 * @since 2.0
 */
public interface ComponentResult {
    /**
     * <p>Returns the identifier of this component. This can be used to uniquely identify the component within the current build, but it is not necessarily unique between
     * different builds.
     *
     * <p>The return type is declared as an opaque {@link ComponentIdentifier}, however the identifier may also implement one of the following interfaces:</p>
     *
     * <ul>
     *     <li>{@link org.gradle.api.artifacts.component.ProjectComponentIdentifier} for those component instances which are produced by the current build.</li>
     *     <li>{@link org.gradle.api.artifacts.component.ModuleComponentIdentifier} for those component instances which are found in some repository.</li>
     * </ul>
     *
     * @return the identifier of this component
     */
    ComponentIdentifier getId();
}
