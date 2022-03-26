package com.tyron.builder.api.internal.buildTree;

public interface BuildTreeLifecycleListener {
    /**
     * Called after the build tree has been created, just after the services have been created.
     *
     * This method is called before the root build operation has started, so implementations should not perform any expensive work
     * and should not run any user code.
     */
    default void afterStart() {
    }

    /**
     * Called just before the build tree is finished with, just prior to closing the build tree services.
     *
     * This method is called after the root build operation has completed, so implementations should not perform any expensive work
     * and should not run any user code.
     */
    default void beforeStop() {
    }
}