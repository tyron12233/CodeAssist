package org.gradle.tooling.internal.protocol.events;

/**
 * DO NOT CHANGE THIS INTERFACE. It is part of the cross-version protocol.
 *
 * Returns build phase details.
 *
 * @since 7.6
 */
public interface InternalBuildPhaseDescriptor extends InternalOperationDescriptor {

    /**
     * Returns the build phase name.
     *
     * Can be one of: CONFIGURE_ROOT_BUILD, CONFIGURE_BUILD, RUN_MAIN_TASKS, RUN_WORK.
     */
    String getBuildPhase();

    /**
     * Returns number of build items this phase will execute.
     */
    int getBuildItemsCount();
}