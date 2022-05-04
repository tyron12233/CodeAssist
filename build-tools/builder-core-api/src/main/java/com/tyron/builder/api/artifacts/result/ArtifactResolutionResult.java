package com.tyron.builder.api.artifacts.result;

import java.util.Set;

/**
 * The result of executing an artifact resolution query.
 *
 * @since 2.0
 */
public interface ArtifactResolutionResult {
    /**
     * <p>Return a set of {@link ComponentResult} instances representing all requested components.
     *
     * <p>Each element in the returned set is declared as an opaque {@link ComponentResult}.
     *    However each element in the result will also implement one of the following interfaces:</p>
     *
     * <ul>
     *     <li>{@link ComponentArtifactsResult} for any component that could be resolved in the set of repositories.</li>
     *     <li>{@link UnresolvedComponentResult} for any component that could not be resolved from the set of repositories.</li>
     * </ul>
     * @return the set of results for all requested components
     */
    Set<ComponentResult> getComponents();

    /**
     * <p>Return a set of {@link ComponentResult} instances representing all successfully resolved components.
     *
     * <p>Calling this method is the same as calling {@link #getComponents()} and filtering the resulting set for elements of type {@link ComponentArtifactsResult}.
     *
     * @return the set of all successfully resolved components
     */
    Set<ComponentArtifactsResult> getResolvedComponents();
}
