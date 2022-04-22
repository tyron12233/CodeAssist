package com.tyron.builder.internal.build;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

/**
 * Listener for changes to the {@link BuildStateRegistry}.
 */
@EventScope(Scopes.BuildTree.class)
public interface BuildAddedListener {
    /**
     * Called every time a build is added to the {@link BuildStateRegistry}.
     *
     * The first call is always for the root build.
     */
    void buildAdded(BuildState buildState);
}
