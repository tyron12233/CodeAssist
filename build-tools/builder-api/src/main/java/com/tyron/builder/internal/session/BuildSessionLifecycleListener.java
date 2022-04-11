package com.tyron.builder.internal.session;

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