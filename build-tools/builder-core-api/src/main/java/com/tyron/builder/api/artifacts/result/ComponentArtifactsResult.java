package com.tyron.builder.api.artifacts.result;

import com.tyron.builder.api.component.Artifact;

import java.util.Set;

/**
 * The result of successfully resolving a component with a set of artifacts.
 *
 * @since 2.0
 */
public interface ComponentArtifactsResult extends ComponentResult {
    /**
     * <p>Returns the artifacts of the specified type that belong to this component. Includes resolved and unresolved artifacts (if any).
     *
     * <p>The elements of the returned collection are declared as {@link ArtifactResult}, however the artifact instances will also implement one of the
     * following instances:</p>
     *
     * <ul>
     *     <li>{@link ResolvedArtifactResult} for artifacts which were successfully resolved.</li>
     *     <li>{@link UnresolvedArtifactResult} for artifacts which could not be resolved for some reason.</li>
     * </ul>
     *
     * @return the artifacts of this component with the specified type, or an empty set if no artifacts of the type exist.
     */
    Set<ArtifactResult> getArtifacts(Class<? extends Artifact> type);
}
