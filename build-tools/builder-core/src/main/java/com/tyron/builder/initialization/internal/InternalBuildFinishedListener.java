package com.tyron.builder.initialization.internal;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

@EventScope(Scopes.Build.class)
public interface InternalBuildFinishedListener {
    /**
     * Called after all user buildFinished hooks have been executed, but before
     * services are shutdown.
     * @param gradle the Gradle instance being finalized
     * @param failed
     */
    default void buildFinished(GradleInternal gradle, boolean failed) {

    }
}