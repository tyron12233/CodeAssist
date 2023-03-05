package org.gradle.initialization.internal;

import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;

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