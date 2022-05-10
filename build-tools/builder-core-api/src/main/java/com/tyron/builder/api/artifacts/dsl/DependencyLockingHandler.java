package com.tyron.builder.api.artifacts.dsl;

import com.tyron.builder.api.file.RegularFileProperty;
import com.tyron.builder.api.provider.ListProperty;
import com.tyron.builder.api.provider.Property;

/**
 * A {@code DependencyLockingHandler} manages the behaviour and configuration of dependency locking.
 *
 * @since 4.8
 */
public interface DependencyLockingHandler {

    /**
     * Convenience method for doing:
     *
     * configurations.all {
     *     resolutionStrategy.activateDependencyLocking()
     * }
     *
     */
    void lockAllConfigurations();

    /**
     * Convenience method for doing:
     *
     * configurations.all {
     *     resolutionStrategy.deactivateDependencyLocking()
     * }
     *
     * @since 6.0
     */
    void unlockAllConfigurations();

    /**
     * Allows to query the lock mode currently configured
     *
     * @since 6.1
     */
    Property<LockMode> getLockMode();

    /**
     * Allows to configure the file used for saving lock state
     * <p>
     * Make sure the lock file is unique per project and separate between the buildscript and project itself.
     * <p>
     * This requires opting in the support for per project single lock file.
     *
     * @since 6.4
     */
    RegularFileProperty getLockFile();

    /**
     * Allows to configure dependencies that will be ignored in the lock state.
     * <p>
     * The format of the entry is {@code <group>:<artifact>} where both can end with a {@code *} as a wildcard character.
     * The value {@code *:*} is not considered a valid value as it is equivalent to disabling locking.
     * <p>
     * These dependencies will not be written to the lock state and any references to them in lock state will be ignored at runtime.
     * It is thus not possible to set this property but still lock a matching entry by manually adding it to the lock state.
     *
     * @since 6.7
     */
    ListProperty<String> getIgnoredDependencies();

}
