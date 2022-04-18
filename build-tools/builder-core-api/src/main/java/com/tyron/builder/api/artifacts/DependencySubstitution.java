package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.internal.HasInternalProtocol;

/**
 * Provides means to substitute a different dependency during resolution.
 *
 * @since 2.5
 */
@HasInternalProtocol
public interface DependencySubstitution {
    /**
     * The requested dependency, before it is resolved.
     * The requested dependency does not change even if there are multiple dependency substitution rules
     * that manipulate the dependency metadata.
     */
    ComponentSelector getRequested();

    /**
     * This method can be used to replace a dependency before it is resolved,
     * e.g. change group, name or version (or all three of them), or replace it
     * with a project dependency.
     *
     * Accepted notations are:
     * <ul>
     *     <li>Strings encoding group:module:version, like 'org.gradle:gradle-core:2.4'</li>
     *     <li>Maps like [group: 'org.gradle', name: 'gradle-core', version: '2.4']</li>
     *     <li>Project instances like <code>project(":api")</code></li>
     *     <li>Any instance of <code>ModuleComponentSelector</code> or <code>ProjectComponentSelector</code></li>
     * </ul>
     *
     * @param notation the notation that gets parsed into an instance of {@link ComponentSelector}.
     */
    void useTarget(Object notation);

    /**
     * This method can be used to replace a dependency before it is resolved,
     * e.g. change group, name or version (or all three of them), or replace it
     * with a project dependency and provides a human readable reason for diagnostics.
     *
     * Accepted notations are:
     * <ul>
     *     <li>Strings encoding group:module:version, like 'org.gradle:gradle-core:2.4'</li>
     *     <li>Maps like [group: 'org.gradle', name: 'gradle-core', version: '2.4']</li>
     *     <li>Project instances like <code>project(":api")</code></li>
     *     <li>Any instance of <code>ModuleComponentSelector</code> or <code>ProjectComponentSelector</code></li>
     * </ul>
     *
     * @param notation the notation that gets parsed into an instance of {@link ComponentSelector}.
     *
     * @since 4.5
     */
    void useTarget(Object notation, String reason);

    /**
     * Configures the artifact selection for the substitution.
     * This is a convenience method which allows selecting, typically, different artifact classifiers
     * for the same component.
     *
     * Artifact selection matters for components which are not published with Gradle Module Metadata
     * and therefore do not provide proper variants to reason with.
     *
     * @param action the artifact selection configuration action
     *
     * @since 6.6
     */
    void artifactSelection(Action<? super ArtifactSelectionDetails> action);
}

