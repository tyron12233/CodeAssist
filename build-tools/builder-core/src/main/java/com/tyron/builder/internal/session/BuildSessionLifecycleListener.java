package com.tyron.builder.internal.session;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

/**
 * A listener that is notified when a session is started and completed. No more than one session may be active at any time.
 *
 * One or more builds may be run during a session. For example, when running in continuous mode, multiple builds are run during a single session.
 */
@EventScope(Scopes.BuildSession.class)
public interface BuildSessionLifecycleListener {
    /**
     * Called at the start of the session, immediately after initializing the session services.
     *
     * This method is called before the root build operation has started, so implementations should not perform any expensive work
     * and should not run any user code.
     */
    default void afterStart() {
    }

    /**
     * Called at the completion of the session, immediately prior to tearing down the session services.
     *
     * This method is called after the root build operation has completed, so implementations should not perform any expensive work
     * and should not run any user code.
     */
    default void beforeComplete() {
    }
}