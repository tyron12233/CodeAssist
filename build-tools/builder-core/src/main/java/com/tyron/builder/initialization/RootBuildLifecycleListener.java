package com.tyron.builder.initialization;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.StatefulListener;

/**
 * A listener that is notified when a root build is started and completed. No more than one root build may run at a given time.
 *
 * A root build may contain zero or more nested builds, such as `buildSrc` or included builds.
 *
 * This listener type is available to services from build tree up to global services.
 */
@EventScope(Scopes.BuildTree.class)
@StatefulListener
public interface RootBuildLifecycleListener {
    /**
     * Called at the start of the root build, immediately after the creation of the root build services.
     */
    void afterStart();

    /**
     * Called at the completion of the root build, immediately before destruction of the root build services.
     */
    void beforeComplete();
}