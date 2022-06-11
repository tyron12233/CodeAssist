package org.gradle.tooling.provider.model.internal;

import org.gradle.internal.build.BuildState;

public interface BuildScopeModelBuilder {
    /**
     * Creates the model for the given target. The target build will not necessarily have been configured.
     * This method is responsible for transitioning the target into the appropriate state required to create the model.
     *
     * No synchronization is applied to the target, so this method may be called for a given target concurrently by multiple threads.
     * Other threads may also be doing work with the target when this method is called.
     * This method is responsible for any synchronization required to create the model.
     */
    Object create(BuildState target);
}
