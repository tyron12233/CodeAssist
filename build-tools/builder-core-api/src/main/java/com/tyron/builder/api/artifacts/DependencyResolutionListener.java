package com.tyron.builder.api.artifacts;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

/**
 * A {@code DependencyResolutionListener} is notified as dependencies are resolved.
 */
@EventScope(Scopes.Build.class)
public interface DependencyResolutionListener {
    /**
     * This method is called immediately before a set of dependencies are resolved.
     *
     * @param dependencies The set of dependencies to be resolved.
     */
    void beforeResolve(ResolvableDependencies dependencies);

    /**
     * This method is called immediately after a set of dependencies are resolved.
     *
     * @param dependencies The set of dependencies resolved.
     */
    void afterResolve(ResolvableDependencies dependencies);
}
